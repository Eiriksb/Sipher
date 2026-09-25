package io.github.eiriksb.sipher.mt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.eiriksb.sipher.runtime.NativeRuntime;
import io.github.eiriksb.sipher.testing.TestDirectories;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Translates the golden sentences with an exported OPUS-MT tiny en→es model and compares with the Python reference
 * decoder. Needs the model files: run with {@code -PmarianTestModel=<dir>}.
 */
class MarianTranslatorTest {
    @Test
    void matchesReferenceDecoder() throws Exception {
        String directory = System.getProperty("sipher.test.marian");
        assumeTrue(directory != null, "no -PmarianTestModel given");
        NativeRuntime.Status natives = NativeRuntime.load(TestDirectories.sipher());
        assumeTrue(natives.translationSupported(), "translation natives unavailable: " + natives.message());

        JsonObject expected = SentencePieceTest.golden().getAsJsonObject("translations");
        try (MarianTranslator translator = MarianTranslator.load(Path.of(directory), 1)) {
            long started = System.nanoTime();
            int mismatches = 0;
            for (Map.Entry<String, JsonElement> entry : expected.entrySet()) {
                // The Python reference translates its input in one pass, so compare without sentence splitting.
                String actual = translator.translateSentence(entry.getKey(), null);
                if (!actual.equals(entry.getValue().getAsString())) {
                    mismatches++;
                    System.out.println("MISMATCH " + entry.getKey() + "\n  java:   " + actual + "\n  python: " + entry.getValue().getAsString());
                }
            }
            System.out.printf("Translated %d sentences in %d ms%n", expected.size(), (System.nanoTime() - started) / 1_000_000);
            assertEquals(0, mismatches, "Java decoding must match the reference decoder");
            assertEquals("¿Puedes oírme? Mi micrófono puede romperse.",
                    translator.translate("Can you hear me? My microphone might be broken.", null));
        }
    }
}
