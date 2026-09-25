package io.github.eiriksb.sipher.client;

import io.github.eiriksb.sipher.Sipher;
import io.github.eiriksb.sipher.asr.SpeechDetector;
import io.github.eiriksb.sipher.asr.SpeechPipeline;
import io.github.eiriksb.sipher.asr.SpeechRecognizer;
import io.github.eiriksb.sipher.config.SipherClientConfig;
import io.github.eiriksb.sipher.models.BuiltinModels;
import io.github.eiriksb.sipher.net.CaptionPayload;
import io.github.eiriksb.sipher.net.CaptionUpdatePayload;
import io.github.eiriksb.sipher.runtime.NativeRuntime;
import io.github.eiriksb.sipher.voice.SipherVoicechatPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Client-side caption engine: loads the speech stack in the background, transcribes the local player's microphone,
 * shows the result, and shares it with the server so nearby players see it.
 */
public final class CaptionEngine {
    public enum State { STARTING, READY, FAILED }

    /** Voice chat stops sending frames when push-to-talk is released; this long without audio ends the utterance. */
    private static final int FLUSH_AFTER_SILENCE_MS = 300;
    private static final long MIN_PARTIAL_SEND_INTERVAL_MS = 350;
    private static final String SPOKEN_LANGUAGE = "en";

    private static volatile State state = State.STARTING;
    private static volatile String message = "Starting";
    private static SpeechPipeline pipeline;
    private static long lastPartialSentAt;

    private CaptionEngine() {
    }

    public static State state() {
        return state;
    }

    public static String message() {
        return message;
    }

    public static void start() {
        Thread init = new Thread(CaptionEngine::initialize, "Sipher init");
        init.setDaemon(true);
        init.start();
    }

    private static void initialize() {
        long started = System.nanoTime();
        try {
            NativeRuntime.Status natives = NativeRuntime.load(Sipher.directory());
            if (!natives.ready()) {
                fail(natives.message());
                return;
            }
            BuiltinModels models = BuiltinModels.extract(Sipher.directory());
            SpeechDetector detector = new SpeechDetector(models.sileroVad(), 0.5f, 0.35f);
            SpeechRecognizer recognizer = SpeechRecognizer.create(models.englishSpeech(), SipherClientConfig.RECOGNIZER_THREADS.get());
            pipeline = new SpeechPipeline("local", detector, recognizer,
                    new SpeechPipeline.Settings(SipherClientConfig.PARTIAL_INTERVAL_MS::get, FLUSH_AFTER_SILENCE_MS),
                    new SpeechPipeline.Listener() {
                        @Override
                        public void onPartial(int utterance, String text) {
                            if (SipherClientConfig.LIVE_PARTIALS.get()) {
                                Minecraft.getInstance().execute(() -> localCaption(utterance, text, true));
                            }
                        }

                        @Override
                        public void onFinal(int utterance, String text) {
                            Minecraft.getInstance().execute(() -> localCaption(utterance, text, false));
                        }
                    });
            SipherVoicechatPlugin.setLocalAudioSink((pcm, whispering) -> {
                if (SipherClientConfig.CAPTIONS_ENABLED.get()) {
                    pipeline.offer(pcm);
                }
            });
            state = State.READY;
            message = natives.message();
            Sipher.LOGGER.info("Sipher speech recognition ready in {} ms", (System.nanoTime() - started) / 1_000_000);
        } catch (Exception | LinkageError e) {
            Sipher.LOGGER.error("Sipher could not start speech recognition", e);
            fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static void fail(String reason) {
        state = State.FAILED;
        message = reason;
    }

    /** The server has Sipher installed and relays captions. */
    public static boolean serverRelays() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(CaptionUpdatePayload.TYPE);
    }

    private static void localCaption(int line, String text, boolean partial) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        UUID self = minecraft.player.getUUID();
        if (text.isEmpty()) {
            CaptionStore.remove(self, line);
            CaptionLog.remove(self, line);
        } else {
            CaptionStore.put(self, line, text, partial);
            CaptionLog.put(self, minecraft.player.getGameProfile().getName(), line, text, partial);
        }

        if (!SipherClientConfig.SHARE_MY_CAPTIONS.get() || !serverRelays()) {
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
        PacketDistributor.sendToServer(new CaptionUpdatePayload(line, partial, SPOKEN_LANGUAGE, text, ""));
    }

    /** A caption relayed by the server from another player. Main thread. */
    public static void remoteCaption(CaptionPayload caption) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || caption.speaker().equals(minecraft.player.getUUID())) {
            return;
        }
        String text = displayText(caption);
        if (text.isEmpty()) {
            CaptionStore.remove(caption.speaker(), caption.line());
            CaptionLog.remove(caption.speaker(), caption.line());
            return;
        }
        CaptionStore.put(caption.speaker(), caption.line(), text, caption.partial());
        CaptionLog.put(caption.speaker(), playerName(caption.speaker()), caption.line(), text, caption.partial());
    }

    /** Picks what to show: the original if it is in the reader's language, otherwise the English pivot if present. */
    static String displayText(CaptionPayload caption) {
        String reading = SipherClientConfig.READING_LANGUAGE.get();
        if (caption.language().equals(reading) || caption.english().isEmpty()) {
            return caption.text();
        }
        return caption.english();
    }

    private static String playerName(UUID player) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(player);
        return info == null ? "?" : info.getProfile().getName();
    }
}
