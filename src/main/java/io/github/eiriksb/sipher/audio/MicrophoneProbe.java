package io.github.eiriksb.sipher.audio;

import io.github.eiriksb.sipher.Sipher;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Development aid, enabled only with {@code -Dsipher.debug.microphone=true}: logs the level of the microphone audio
 * Sipher receives and appends it to {@code <game>/sipher/debug/mic-<time>.pcm} (48 kHz mono 16-bit little-endian), so
 * recognition problems can be reproduced offline. Release builds never enable it.
 */
public final class MicrophoneProbe {
    private static final boolean ENABLED = Boolean.getBoolean("sipher.debug.microphone");
    private static final int FRAMES_PER_REPORT = 100; // 2 s

    private final OutputStream out;
    private int frames;
    private double sumSquares;
    private int peak;
    private long samples;

    private MicrophoneProbe(OutputStream out) {
        this.out = out;
    }

    /** A probe when debugging is enabled, otherwise {@code null}. */
    public static MicrophoneProbe create(Path sipherDirectory) {
        if (!ENABLED) {
            return null;
        }
        try {
            Path directory = Files.createDirectories(sipherDirectory.resolve("debug"));
            Path file = directory.resolve("mic-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".pcm");
            Sipher.LOGGER.info("Recording received microphone audio to {} (48 kHz mono s16le)", file);
            return new MicrophoneProbe(Files.newOutputStream(file));
        } catch (IOException e) {
            Sipher.LOGGER.warn("Could not start the microphone probe", e);
            return null;
        }
    }

    /** Called on the voice chat microphone thread for every frame Sipher receives. */
    public synchronized void accept(short[] pcm) {
        ByteBuffer bytes = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : pcm) {
            bytes.putShort(sample);
            sumSquares += (double) sample * sample;
            peak = Math.max(peak, Math.abs((int) sample));
        }
        samples += pcm.length;
        try {
            out.write(bytes.array());
            out.flush();
        } catch (IOException e) {
            Sipher.LOGGER.debug("Microphone probe write failed", e);
        }
        if (++frames % FRAMES_PER_REPORT == 0) {
            double rms = Math.sqrt(sumSquares / samples);
            Sipher.LOGGER.info("Microphone level over last 2 s: RMS {} dBFS, peak {} dBFS",
                    String.format("%.1f", 20 * Math.log10(Math.max(rms, 1) / 32768)),
                    String.format("%.1f", 20 * Math.log10(Math.max(peak, 1) / 32768.0)));
            sumSquares = 0;
            peak = 0;
            samples = 0;
        }
    }
}
