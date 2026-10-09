package io.github.eiriksb.sipher.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A recording played into Sipher as if it came from Simple Voice Chat's microphone, to test speech recognition without
 * one: 48 kHz mono frames of 20 ms, in real time, the recording and then a few seconds of silence, over and over.
 * Only used when a developer asks for it.
 */
public final class DebugAudio {
    public static final int SAMPLE_RATE = 48_000;
    /** Simple Voice Chat's frame: 20 ms. */
    public static final int FRAME_SAMPLES = 960;
    private static final int SILENCE_FRAMES = 150; // 3 s, so the speech detector ends the utterance

    private DebugAudio() {
    }

    /** Reads a 16-bit mono WAV file and resamples it to 48 kHz. */
    public static short[] read(Path file) throws IOException {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat format = in.getFormat();
            if (format.getChannels() != 1 || format.getSampleSizeInBits() != 16
                    || format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED || format.isBigEndian()) {
                throw new IOException("Expected a 16-bit little-endian mono WAV file, got " + format);
            }
            byte[] bytes = in.readAllBytes();
            short[] source = new short[bytes.length / 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(source);
            return resample(source, format.getSampleRate());
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Not a WAV file: " + file, e);
        }
    }

    /** Linear interpolation to 48 kHz; Sipher's own downsampler filters it on the way to 16 kHz. */
    static short[] resample(short[] source, float rate) {
        int length = (int) (source.length * (double) SAMPLE_RATE / rate);
        short[] out = new short[length];
        for (int i = 0; i < length; i++) {
            double position = i * rate / SAMPLE_RATE;
            int index = (int) position;
            double fraction = position - index;
            int next = Math.min(source.length - 1, index + 1);
            out[i] = (short) Math.round(source[index] * (1 - fraction) + source[next] * fraction);
        }
        return out;
    }

    /** One pass of what {@link #play} delivers: the recording in 20 ms frames, padded with silence. */
    static short[][] frames(short[] samples) {
        int speechFrames = (samples.length + FRAME_SAMPLES - 1) / FRAME_SAMPLES;
        short[][] frames = new short[speechFrames + SILENCE_FRAMES][];
        for (int i = 0; i < frames.length; i++) {
            int from = i * FRAME_SAMPLES;
            frames[i] = from < samples.length ? Arrays.copyOfRange(samples, from, from + FRAME_SAMPLES) : new short[FRAME_SAMPLES];
        }
        return frames;
    }

    /** Hands {@code sink} one frame every 20 ms on a daemon thread, until the game exits. */
    public static void play(short[] samples, Consumer<short[]> sink) {
        short[][] frames = frames(samples);
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Sipher debug audio");
            thread.setDaemon(true);
            return thread;
        });
        int[] next = {0};
        timer.scheduleAtFixedRate(() -> {
            sink.accept(frames[next[0]]);
            next[0] = (next[0] + 1) % frames.length;
        }, 0, 20, TimeUnit.MILLISECONDS);
    }
}
