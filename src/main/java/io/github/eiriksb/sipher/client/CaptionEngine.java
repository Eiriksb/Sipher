package io.github.eiriksb.sipher.client;

import io.github.eiriksb.sipher.Sipher;
import io.github.eiriksb.sipher.asr.SpeechDetector;
import io.github.eiriksb.sipher.audio.MicrophoneProbe;
import io.github.eiriksb.sipher.asr.SpeechPipeline;
import io.github.eiriksb.sipher.asr.SpeechRecognizer;
import io.github.eiriksb.sipher.config.SipherClientConfig;
import io.github.eiriksb.sipher.models.BuiltinModels;
import io.github.eiriksb.sipher.models.Catalog;
import io.github.eiriksb.sipher.models.LanguagePacks;
import io.github.eiriksb.sipher.mt.MarianTranslator;
import io.github.eiriksb.sipher.net.CaptionPayload;
import io.github.eiriksb.sipher.net.CaptionUpdatePayload;
import io.github.eiriksb.sipher.runtime.NativeRuntime;
import io.github.eiriksb.sipher.voice.SipherVoicechatPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side caption engine.
 *
 * <p>Speaking: the local microphone is transcribed in the player's spoken language and, if that isn't English,
 * translated to English on the spot. Captions carry both texts ({@code language}, {@code text}, {@code english}).
 *
 * <p>Reading: other players' captions are shown as-is when they are in the reader's language, as the English pivot
 * when the reader reads English, and otherwise translated from English into the reader's language. So every player only
 * ever needs the language pack for their own language.
 */
public final class CaptionEngine {
    public enum State { STARTING, READY, NEEDS_PACK, FAILED }

    /** Voice chat stops sending frames when push-to-talk is released; this long without audio ends the utterance. */
    private static final int FLUSH_AFTER_SILENCE_MS = 300;
    private static final long MIN_PARTIAL_SEND_INTERVAL_MS = 350;
    private static final int TRANSLATOR_THREADS = 2;

    private static final ExecutorService MODELS = daemonExecutor("Sipher models");
    private static final ExecutorService TRANSLATE = daemonExecutor("Sipher translate");
    private static final Map<String, MarianTranslator> TRANSLATORS = new ConcurrentHashMap<>();
    private static final Map<LineKey, String> PENDING = new ConcurrentHashMap<>();

    private static volatile State state = State.STARTING;
    private static volatile String message = "Starting";
    private static volatile Catalog catalog;
    private static volatile LanguagePacks packs;
    private static volatile Speech speech;
    private static volatile MarianTranslator reader;
    private static volatile String readerLanguage = "en";
    private static BuiltinModels builtin;
    private static long lastPartialSentAt;
    private static volatile long lastMicrophoneAudio;

    private record Speech(String language, SpeechPipeline pipeline, SpeechDetector detector, SpeechRecognizer recognizer,
                          MarianTranslator toEnglish) {
    }

    private record LineKey(UUID speaker, int line) {
    }

    private CaptionEngine() {
    }

    public static State state() {
        return state;
    }

    public static String message() {
        return message;
    }

    /** Whether Simple Voice Chat delivered microphone audio in the last second (it only does while transmitting). */
    public static boolean hearingMicrophone() {
        return System.currentTimeMillis() - lastMicrophoneAudio < 1000;
    }

    /** Language packs, or {@code null} while the engine is still starting. */
    public static LanguagePacks packs() {
        return packs;
    }

    public static void start() {
        MODELS.execute(CaptionEngine::initialize);
    }

    /** Re-reads the spoken and reading language settings and loads whatever models they need. */
    public static void reconfigure() {
        MODELS.execute(() -> {
            if (builtin != null) {
                configureSpeech();
                configureReading();
            }
        });
    }

    private static void initialize() {
        long started = System.nanoTime();
        try {
            NativeRuntime.Status natives = NativeRuntime.load(Sipher.directory());
            if (!natives.ready()) {
                fail(natives.message());
                return;
            }
            builtin = BuiltinModels.extract(Sipher.directory());
            try {
                catalog = Catalog.load();
            } catch (Exception e) {
                // English captions don't need the catalogue; keep them working even if it is broken.
                Sipher.LOGGER.error("Could not read the Sipher language pack catalogue", e);
                catalog = Catalog.empty();
            }
            String version = ModList.get().getModContainerById(Sipher.MOD_ID)
                    .map(mod -> mod.getModInfo().getVersion().toString()).orElse("dev");
            packs = new LanguagePacks(catalog, Sipher.directory(), "Sipher/" + version);
            MicrophoneProbe probe = MicrophoneProbe.create(Sipher.directory());
            SipherVoicechatPlugin.setLocalAudioSink((pcm, whispering) -> {
                if (probe != null) {
                    probe.accept(pcm);
                }
                if (lastMicrophoneAudio == 0) {
                    Sipher.LOGGER.info("Receiving microphone audio from Simple Voice Chat ({} samples per frame)", pcm.length);
                }
                lastMicrophoneAudio = System.currentTimeMillis();
                Speech current = speech;
                if (current != null && SipherClientConfig.CAPTIONS_ENABLED.get()) {
                    current.pipeline().offer(pcm);
                }
            });
            configureSpeech();
            configureReading();
            Sipher.LOGGER.info("Sipher ready in {} ms ({})", (System.nanoTime() - started) / 1_000_000, natives.message());
        } catch (Exception | LinkageError e) {
            Sipher.LOGGER.error("Sipher could not start speech recognition", e);
            fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Models thread only. */
    private static void configureSpeech() {
        String language = SipherClientConfig.SPOKEN_LANGUAGE.get();
        Speech previous = speech;
        if (previous != null && previous.language().equals(language)) {
            return;
        }
        speech = null;
        if (previous != null) {
            previous.pipeline().close();
            previous.recognizer().close();
            previous.detector().close();
        }

        try {
            SpeechRecognizer recognizer;
            MarianTranslator toEnglish = null;
            int threads = SipherClientConfig.RECOGNIZER_THREADS.get();
            if (language.equals("en")) {
                recognizer = SpeechRecognizer.create(builtin.englishSpeech(), threads);
            } else {
                Catalog.Language pack = catalog.language(language).orElse(null);
                if (pack == null || !packs.installed(pack)) {
                    state = State.NEEDS_PACK;
                    message = pack == null ? "Unknown language " + language : pack.englishName() + " language pack not installed";
                    Sipher.LOGGER.info("Captions paused: {}", message);
                    return;
                }
                recognizer = SpeechRecognizer.create(
                        catalog.component(pack.speech()).asrModel(packs.directory(pack.speech()), language), threads);
                toEnglish = translator(pack.toEnglish());
            }
            SpeechDetector detector = new SpeechDetector(builtin.sileroVad(), 0.5f, 0.35f);
            MarianTranslator pivot = toEnglish;
            SpeechPipeline pipeline = new SpeechPipeline("local", detector, recognizer,
                    new SpeechPipeline.Settings(SipherClientConfig.PARTIAL_INTERVAL_MS::get, FLUSH_AFTER_SILENCE_MS),
                    new SpeechPipeline.Listener() {
                        @Override
                        public void onPartial(int utterance, String text) {
                            if (SipherClientConfig.LIVE_PARTIALS.get()) {
                                deliver(utterance, text, true, language, pivot);
                            }
                        }

                        @Override
                        public void onFinal(int utterance, String text) {
                            deliver(utterance, text, false, language, pivot);
                        }
                    });
            speech = new Speech(language, pipeline, detector, recognizer, toEnglish);
            state = State.READY;
            message = language.equals("en") ? "English" : catalog.language(language).map(Catalog.Language::englishName).orElse(language);
            Sipher.LOGGER.info("Captions: speaking {} with {} ({}){}", language, recognizer.model().id(), recognizer.model().kind(),
                    toEnglish == null ? "" : ", translating to English");
        } catch (Exception e) {
            Sipher.LOGGER.error("Could not load speech recognition for {}", language, e);
            fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Models thread only. */
    private static void configureReading() {
        String language = SipherClientConfig.READING_LANGUAGE.get();
        readerLanguage = language;
        reader = null;
        if (!language.equals("en")) {
            catalog.language(language)
                    .filter(pack -> pack.fromEnglish() != null && packs.installed(pack.fromEnglish()))
                    .ifPresent(pack -> reader = translator(pack.fromEnglish()));
        }
        Set<String> needed = new java.util.HashSet<>();
        Speech current = speech;
        if (current != null && current.toEnglish() != null) {
            catalog.language(current.language()).ifPresent(pack -> needed.add(pack.toEnglish()));
        }
        catalog.language(language).map(Catalog.Language::fromEnglish).ifPresent(needed::add);
        TRANSLATORS.entrySet().removeIf(entry -> {
            if (needed.contains(entry.getKey())) {
                return false;
            }
            try {
                entry.getValue().close();
            } catch (Exception ignored) {
                // native session already gone
            }
            return true;
        });
    }

    private static MarianTranslator translator(String componentId) {
        return TRANSLATORS.computeIfAbsent(componentId, id -> {
            try {
                return MarianTranslator.load(packs.directory(id), TRANSLATOR_THREADS);
            } catch (Exception e) {
                Sipher.LOGGER.error("Could not load translation model {}", id, e);
                return null;
            }
        });
    }

    private static void fail(String reason) {
        state = State.FAILED;
        message = reason;
    }

    /** Speech thread: translate our own caption to English (if needed), then hand it to the main thread. */
    private static void deliver(int line, String text, boolean partial, String language, MarianTranslator toEnglish) {
        String english = "";
        if (toEnglish != null && !text.isEmpty()) {
            try {
                english = toEnglish.translate(text, null);
            } catch (Exception e) {
                Sipher.LOGGER.debug("Could not translate own caption to English", e);
            }
        }
        String pivot = english;
        Minecraft.getInstance().execute(() -> localCaption(line, text, pivot, partial, language));
    }

    /** The server has Sipher installed and relays captions. */
    public static boolean serverRelays() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(CaptionUpdatePayload.TYPE);
    }

    private static void localCaption(int line, String text, String english, boolean partial, String language) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Sipher.LOGGER.debug("Local caption {} ({}, {}): {} | {}", line, partial ? "live" : "final", language, text, english);
        // Our own captions follow the "Captions in" setting just like everyone else's.
        display(new CaptionPayload(minecraft.player.getUUID(), line, partial, language, text, english));

        // Nothing is shared before the player has chosen on the welcome screen.
        if (!SipherClientConfig.WELCOME_SEEN.get() || !SipherClientConfig.SHARE_MY_CAPTIONS.get() || !serverRelays()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (partial) {
            if (now - lastPartialSentAt < MIN_PARTIAL_SEND_INTERVAL_MS) {
                return;
            }
            lastPartialSentAt = now;
        }
        // An empty final tells listeners to drop the live caption of an utterance that turned out to be noise.
        PacketDistributor.sendToServer(new CaptionUpdatePayload(line, partial, language, text, english));
    }

    /** A caption relayed by the server from another player. Main thread. */
    public static void remoteCaption(CaptionPayload caption) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || caption.speaker().equals(minecraft.player.getUUID())) {
            return;
        }
        Sipher.LOGGER.debug("Caption from {} line {} ({}, {}): {} | {}", caption.speaker(), caption.line(),
                caption.partial() ? "live" : "final", caption.language(), caption.text(), caption.english());
        display(caption);
    }

    /**
     * Shows a caption in the reader's language: the original if it is already in that language, otherwise the English
     * pivot right away, replaced by a translation from English once it is ready. Main thread.
     */
    private static void display(CaptionPayload caption) {
        LineKey key = new LineKey(caption.speaker(), caption.line());
        String shown = immediateText(caption);
        if (shown.isEmpty()) {
            PENDING.remove(key);
            CaptionStore.remove(caption.speaker(), caption.line());
            CaptionLog.remove(caption.speaker(), caption.line());
            return;
        }
        show(caption, shown);

        String source = translationSource(caption);
        MarianTranslator translator = reader;
        String language = readerLanguage;
        if (source == null || translator == null) {
            return;
        }
        // Newest wins: a live caption can be superseded before its translation starts.
        PENDING.put(key, source);
        TRANSLATE.execute(() -> {
            if (!source.equals(PENDING.get(key))) {
                return;
            }
            try {
                Catalog.Language pack = catalog.language(language).orElseThrow();
                String translated = translator.translate(source, catalog.component(pack.fromEnglish()).targetToken());
                Minecraft.getInstance().execute(() -> {
                    if (source.equals(PENDING.get(key)) && !translated.isEmpty()) {
                        if (!caption.partial()) {
                            PENDING.remove(key);
                        }
                        show(caption, translated);
                    }
                });
            } catch (Exception e) {
                Sipher.LOGGER.debug("Could not translate caption into {}", language, e);
            }
        });
    }

    private static void show(CaptionPayload caption, String text) {
        CaptionStore.put(caption.speaker(), caption.line(), text, caption.partial());
        CaptionLog.put(caption.speaker(), speakerName(caption.speaker()), caption.line(), text, caption.partial());
    }

    /** What to show right away: the original in the reader's language, otherwise the English pivot. */
    static String immediateText(CaptionPayload caption) {
        if (caption.language().equals(readerLanguage) || caption.english().isEmpty()) {
            return caption.text();
        }
        return caption.english();
    }

    /** English text to translate into the reader's language, or {@code null} when no translation is needed. */
    static String translationSource(CaptionPayload caption) {
        if (readerLanguage.equals("en") || caption.language().equals(readerLanguage)) {
            return null;
        }
        if (!caption.english().isEmpty()) {
            return caption.english();
        }
        return caption.language().equals("en") ? caption.text() : null;
    }

    /** A player's name, or the name of another entity a server mod sends captions for (a talking villager). */
    private static String speakerName(UUID speaker) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(speaker);
        if (info != null) {
            return info.getProfile().getName();
        }
        if (minecraft.level != null) {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity.getUUID().equals(speaker)) {
                    return entity.getDisplayName().getString();
                }
            }
        }
        return "?";
    }

    private static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
    }

    /** For the Languages screen: display names by code, English first. */
    public static Map<String, String> languageNames() {
        Map<String, String> names = new HashMap<>();
        names.put("en", "English");
        Catalog current = catalog;
        if (current != null) {
            current.languages().forEach(language -> names.put(language.code(), language.name()));
        }
        return names;
    }
}
