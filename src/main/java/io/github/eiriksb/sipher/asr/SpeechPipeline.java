package io.github.eiriksb.sipher.asr;

import io.github.eiriksb.sipher.Sipher;
import io.github.eiriksb.sipher.audio.Downsampler;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Turns one speaker's 48 kHz voice chat frames into captions on a dedicated low-priority thread.
 *
 * <p>Frames are resampled to 16 kHz and run through voice activity detection. While someone talks, the audio of the
 * current utterance is re-transcribed every {@code partialIntervalMs} to produce a live partial caption; when the
 * detector closes the utterance (a pause, the 8 s cap, or the speaker going quiet on voice chat) the whole segment is
 * transcribed once more as the final caption.
 */
public final class SpeechPipeline implements AutoCloseable {
    public interface Listener {
        void onPartial(int utterance, String text);

        /** Called once per utterance; {@code text} is empty when nothing worth showing was said. */
        void onFinal(int utterance, String text);
    }

    public record Settings(IntSupplier partialIntervalMs, int flushAfterSilenceMs) {
    }

    private static final int QUEUE_FRAMES = 250; // 5 s of 20 ms frames
    private static final int PREROLL_SAMPLES = SpeechRecognizer.SAMPLE_RATE * 3 / 10;
    private static final int MIN_PARTIAL_SAMPLES = SpeechRecognizer.SAMPLE_RATE * 6 / 10;

    private final SpeechDetector detector;
    private final SpeechRecognizer recognizer;
    private final Listener listener;
    private final Settings settings;
    private final BlockingQueue<short[]> frames = new ArrayBlockingQueue<>(QUEUE_FRAMES);
    private final Downsampler downsampler = new Downsampler();
    private final ArrayDeque<float[]> preroll = new ArrayDeque<>();
    private final Thread thread;
    private volatile boolean running = true;
    private volatile long droppedFrames;

    private float[] utterance = new float[SpeechRecognizer.SAMPLE_RATE * 10];
    private int utteranceLength;
    private int prerollLength;
    private boolean inUtterance;
    private boolean pendingAudio;
    private int utteranceId;
    private long lastAudioNanos;
    private long lastPartialNanos;
    private String lastPartial = "";

    public SpeechPipeline(String name, SpeechDetector detector, SpeechRecognizer recognizer, Settings settings, Listener listener) {
        this.detector = detector;
        this.recognizer = recognizer;
        this.settings = settings;
        this.listener = listener;
        this.thread = new Thread(this::run, "Sipher speech " + name);
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.NORM_PRIORITY - 1);
        this.thread.start();
    }

    /** Queues one frame of 48 kHz mono PCM. Never blocks; drops audio if the worker has fallen far behind. */
    public void offer(short[] pcm) {
        if (pcm == null || pcm.length == 0 || !running) {
            return;
        }
        if (!frames.offer(pcm.clone())) {
            droppedFrames++;
        }
    }

    public long droppedFrames() {
        return droppedFrames;
    }

    private void run() {
        while (running) {
            try {
                short[] frame = frames.poll(40, TimeUnit.MILLISECONDS);
                long now = System.nanoTime();
                if (frame != null) {
                    lastAudioNanos = now;
                    process(downsampler.process(frame), now);
                } else if (pendingAudio && now - lastAudioNanos > TimeUnit.MILLISECONDS.toNanos(settings.flushAfterSilenceMs())) {
                    // The speaker stopped transmitting (push-to-talk released or voice activation closed).
                    finish(detector.flush());
                    downsampler.reset();
                    preroll.clear();
                    prerollLength = 0;
                    pendingAudio = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                Sipher.LOGGER.error("Speech pipeline failed; resetting it", e);
                resetUtterance();
            }
        }
    }

    private void process(float[] samples, long now) {
        if (samples.length == 0) {
            return;
        }
        pendingAudio = true;
        detector.accept(samples);

        if (!inUtterance && detector.speaking()) {
            Sipher.LOGGER.debug("Speech detected (utterance {})", utteranceId + 1);
            inUtterance = true;
            utteranceId++;
            utteranceLength = 0;
            lastPartial = "";
            lastPartialNanos = now;
            for (float[] chunk : preroll) {
                append(chunk);
            }
        }
        if (inUtterance) {
            append(samples);
        } else {
            remember(samples);
        }

        List<float[]> segments = detector.takeSegments();
        if (!segments.isEmpty()) {
            finish(segments);
        } else if (inUtterance && utteranceLength >= MIN_PARTIAL_SAMPLES
                && now - lastPartialNanos >= TimeUnit.MILLISECONDS.toNanos(settings.partialIntervalMs().getAsInt())) {
            lastPartialNanos = now;
            String text = TranscriptFilter.clean(recognizer.transcribe(Arrays.copyOf(utterance, utteranceLength)));
            if (!text.isEmpty() && !text.equals(lastPartial)) {
                lastPartial = text;
                listener.onPartial(utteranceId, text);
            }
        }
    }

    private void finish(List<float[]> segments) {
        if (segments.isEmpty()) {
            if (inUtterance) {
                listener.onFinal(utteranceId, "");
            }
            resetUtterance();
            return;
        }
        for (int i = 0; i < segments.size(); i++) {
            if (!inUtterance || i > 0) {
                utteranceId++;
            }
            listener.onFinal(utteranceId, TranscriptFilter.clean(recognizer.transcribe(segments.get(i))));
            inUtterance = false;
        }
        resetUtterance();
        // The detector may still be inside speech when it split a long utterance at the 8 s cap.
        if (detector.speaking()) {
            inUtterance = true;
            utteranceId++;
            lastPartialNanos = System.nanoTime();
        }
    }

    private void resetUtterance() {
        inUtterance = false;
        utteranceLength = 0;
        lastPartial = "";
    }

    private void append(float[] samples) {
        if (utteranceLength + samples.length > utterance.length) {
            utterance = Arrays.copyOf(utterance, Math.max(utterance.length * 2, utteranceLength + samples.length));
        }
        System.arraycopy(samples, 0, utterance, utteranceLength, samples.length);
        utteranceLength += samples.length;
    }

    private void remember(float[] samples) {
        preroll.addLast(samples);
        prerollLength += samples.length;
        while (prerollLength - preroll.peekFirst().length >= PREROLL_SAMPLES) {
            prerollLength -= preroll.removeFirst().length;
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
