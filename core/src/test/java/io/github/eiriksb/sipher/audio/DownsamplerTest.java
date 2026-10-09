package io.github.eiriksb.sipher.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownsamplerTest {
    @Test
    void keepsSpeechBandTones() {
        double rms = outputRms(1_000);
        assertEquals(0.5 / Math.sqrt(2), rms, 0.02, "a 1 kHz tone should pass at full amplitude");
    }

    @Test
    void removesTonesAboveTheNewNyquistInsteadOfAliasingThem() {
        // 12 kHz would alias to 4 kHz — right in the speech band — without the low-pass filter.
        assertTrue(outputRms(12_000) < 0.005, "a 12 kHz tone must be filtered out");
    }

    @Test
    void producesOneThirdOfTheSamplesAcrossFrames() {
        Downsampler downsampler = new Downsampler();
        int produced = 0;
        for (int frame = 0; frame < 50; frame++) {
            produced += downsampler.process(new short[960]).length;
        }
        // 50 frames × 320 samples, minus the filter's start-up delay.
        assertTrue(produced > 50 * 320 - 32 && produced <= 50 * 320, "got " + produced);
    }

    private static double outputRms(double frequency) {
        Downsampler downsampler = new Downsampler();
        double sum = 0;
        int count = 0;
        for (int frame = 0; frame < 100; frame++) {
            short[] pcm = new short[960];
            for (int i = 0; i < pcm.length; i++) {
                double t = (frame * 960 + i) / (double) Downsampler.INPUT_RATE;
                pcm[i] = (short) Math.round(0.5 * 32767 * Math.sin(2 * Math.PI * frequency * t));
            }
            float[] out = downsampler.process(pcm);
            if (frame < 10) {
                continue; // skip the filter warm-up
            }
            for (float sample : out) {
                sum += sample * sample;
                count++;
            }
        }
        return Math.sqrt(sum / count);
    }
}
