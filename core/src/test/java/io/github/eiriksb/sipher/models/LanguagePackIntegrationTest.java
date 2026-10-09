package io.github.eiriksb.sipher.models;

import io.github.eiriksb.sipher.asr.SpeechRecognizer;
import io.github.eiriksb.sipher.mt.MarianTranslator;
import io.github.eiriksb.sipher.runtime.NativeRuntime;
import io.github.eiriksb.sipher.testing.TestDirectories;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads every language pack the way the game does (catalogue entry → recogniser / translators) and runs it on real
 * speech. Needs locally built packs: {@code -PpacksDir=.cache/packs/out -PpacksAudio=.cache/packs/work}.
 */
class LanguagePackIntegrationTest {
    private static final Map<String, String> FLEURS = Map.ofEntries(
            Map.entry("es", "es_419"), Map.entry("de", "de_de"), Map.entry("fr", "fr_fr"), Map.entry("it", "it_it"),
            Map.entry("nl", "nl_nl"), Map.entry("pt", "pt_br"), Map.entry("pl", "pl_pl"), Map.entry("ru", "ru_ru"),
            Map.entry("nb", "nb_no"), Map.entry("sv", "sv_se"), Map.entry("da", "da_dk"), Map.entry("fi", "fi_fi"),
            Map.entry("zh", "cmn_hans_cn"), Map.entry("ja", "ja_jp"), Map.entry("ko", "ko_kr"));

    @Test
    void everyBuiltPackTranscribesAndTranslates() throws Exception {
        String packs = System.getProperty("sipher.test.packs");
        assumeTrue(packs != null, "no -PpacksDir given");
        Path packsDir = Path.of(packs);
        Path audioDir = Path.of(System.getProperty("sipher.test.packsAudio", packs));
        NativeRuntime.Status natives = NativeRuntime.load(TestDirectories.sipher());
        TestDirectories.requireNatives(natives.translationSupported(), natives.message());

        Catalog catalog = Catalog.load();
        List<String> failures = new ArrayList<>();
        int tested = 0;
        for (Catalog.Language language : catalog.languages()) {
            if (!language.components().stream().allMatch(id -> Files.isRegularFile(packsDir.resolve(id).resolve("manifest.json")))) {
                continue;
            }
            tested++;
            try {
                Path clip = firstClip(audioDir, FLEURS.get(language.code()));
                String heard = "";
                if (clip != null) {
                    Catalog.Component speech = catalog.component(language.speech());
                    try (SpeechRecognizer recognizer = SpeechRecognizer.create(
                            speech.asrModel(packsDir.resolve(language.speech()), language.code()), 2)) {
                        heard = recognizer.transcribe(read16k(clip));
                    }
                    if (heard.isBlank()) {
                        failures.add(language.code() + ": empty transcript");
                    }
                }
                String english;
                try (MarianTranslator toEnglish = MarianTranslator.load(packsDir.resolve(language.toEnglish()), 2)) {
                    english = heard.isBlank() ? "" : toEnglish.translate(heard, null);
                }
                String translated;
                try (MarianTranslator fromEnglish = MarianTranslator.load(packsDir.resolve(language.fromEnglish()), 2)) {
                    translated = fromEnglish.translate("Watch out, there's a creeper behind you!",
                            catalog.component(language.fromEnglish()).targetToken());
                }
                if (translated.isBlank() || (!heard.isBlank() && english.isBlank())) {
                    failures.add(language.code() + ": empty translation");
                }
                System.out.printf("%s  heard: %s%n    → en: %s%n    creeper → %s%n", language.code(), heard, english, translated);
            } catch (Exception e) {
                failures.add(language.code() + ": " + e);
            }
        }
        assertTrue(tested > 0, "no built packs found in " + packsDir);
        assertFalse(!failures.isEmpty(), String.join("\n", failures));
    }

    private static Path firstClip(Path audioDir, String fleursLanguage) throws Exception {
        if (fleursLanguage == null) {
            return null;
        }
        try (Stream<Path> files = Files.walk(audioDir)) {
            return files.filter(p -> p.toString().endsWith(".wav") && p.getParent().getFileName().toString().equals(fleursLanguage))
                    .sorted().findFirst().orElse(null);
        }
    }

    private static float[] read16k(Path wav) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            float rate = in.getFormat().getSampleRate();
            ByteBuffer bytes = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            float[] samples;
            if (in.getFormat().getEncoding() == AudioFormat.Encoding.PCM_FLOAT) {
                samples = new float[bytes.remaining() / 4];
                bytes.asFloatBuffer().get(samples);
            } else {
                short[] pcm = new short[bytes.remaining() / 2];
                bytes.asShortBuffer().get(pcm);
                samples = new float[pcm.length];
                for (int i = 0; i < pcm.length; i++) {
                    samples[i] = pcm[i] / 32768f;
                }
            }
            int length = (int) (samples.length * 16_000L / rate);
            float[] out = new float[length];
            for (int i = 0; i < length; i++) {
                out[i] = samples[Math.min(samples.length - 1, (int) (i * rate / 16_000))];
            }
            return out;
        }
    }
}
