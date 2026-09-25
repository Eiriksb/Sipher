package io.github.eiriksb.sipher.audio;

import java.util.Arrays;

/**
 * Streaming 48 kHz → 16 kHz converter for Simple Voice Chat PCM.
 *
 * A windowed-sinc low-pass (cut-off 7.2 kHz, Blackman window) runs before keeping every third sample, so energy above
 * the new Nyquist frequency does not alias into the speech band. State carries across calls, so 20 ms voice chat
 * frames can be fed one at a time.
 */
public final class Downsampler {
    public static final int INPUT_RATE = 48_000;
    public static final int OUTPUT_RATE = 16_000;
    private static final int FACTOR = INPUT_RATE / OUTPUT_RATE;
    private static final float[] TAPS = lowPass(63, 7_200.0 / INPUT_RATE);

    private float[] buffer = new float[4096];
    private int buffered;

    /** Converts 16-bit PCM at 48 kHz to normalised float samples at 16 kHz. */
    public float[] process(short[] pcm) {
        ensureCapacity(buffered + pcm.length);
        for (int i = 0; i < pcm.length; i++) {
            buffer[buffered + i] = pcm[i] / 32768f;
        }
        buffered += pcm.length;

        int available = buffered - TAPS.length + 1;
        if (available <= 0) {
            return new float[0];
        }
        int outputs = (available + FACTOR - 1) / FACTOR;
        float[] out = new float[outputs];
        for (int o = 0; o < outputs; o++) {
            int start = o * FACTOR;
            float sum = 0f;
            for (int k = 0; k < TAPS.length; k++) {
                sum += buffer[start + k] * TAPS[k];
            }
            out[o] = sum;
        }

        int consumed = outputs * FACTOR;
        System.arraycopy(buffer, consumed, buffer, 0, buffered - consumed);
        buffered -= consumed;
        return out;
    }

    public void reset() {
        buffered = 0;
    }

    private void ensureCapacity(int capacity) {
        if (capacity > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(capacity, buffer.length * 2));
        }
    }

    static float[] lowPass(int length, double cutoff) {
        float[] taps = new float[length];
        int middle = (length - 1) / 2;
        double sum = 0;
        for (int n = 0; n < length; n++) {
            int m = n - middle;
            double sinc = m == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * m) / (Math.PI * m);
            double window = 0.42 - 0.5 * Math.cos(2 * Math.PI * n / (length - 1)) + 0.08 * Math.cos(4 * Math.PI * n / (length - 1));
            taps[n] = (float) (sinc * window);
            sum += taps[n];
        }
        for (int n = 0; n < length; n++) {
            taps[n] /= (float) sum;
        }
        return taps;
    }
}
