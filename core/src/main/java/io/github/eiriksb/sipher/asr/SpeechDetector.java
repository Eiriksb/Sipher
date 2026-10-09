package io.github.eiriksb.sipher.asr;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Silero voice activity detection (via sherpa-onnx) that turns a 16 kHz stream into speech segments. */
public final class SpeechDetector implements AutoCloseable {
    /**
     * Moonshine in sherpa-onnx 1.13.8 returns empty text for audio longer than ~9.2 s, so long speech is split into
     * segments of at most this many seconds.
     */
    public static final float MAX_SEGMENT_SECONDS = 8.0f;

    private final Vad vad;

    public SpeechDetector(Path sileroModel, float threshold, float minSilenceSeconds) {
        SileroVadModelConfig silero = SileroVadModelConfig.builder()
                .setModel(sileroModel.toString())
                .setThreshold(threshold)
                .setMinSilenceDuration(minSilenceSeconds)
                .setMinSpeechDuration(0.25f)
                .setWindowSize(512)
                .setMaxSpeechDuration(MAX_SEGMENT_SECONDS)
                .build();
        this.vad = new Vad(VadModelConfig.builder()
                .setSileroVadModelConfig(silero)
                .setSampleRate(16_000)
                .setNumThreads(1)
                .setDebug(false)
                .build());
    }

    public void accept(float[] samples) {
        if (samples.length > 0) {
            vad.acceptWaveform(samples);
        }
    }

    public boolean speaking() {
        return vad.isSpeechDetected();
    }

    /** Completed speech segments since the last call. */
    public List<float[]> takeSegments() {
        List<float[]> segments = new ArrayList<>();
        while (!vad.empty()) {
            SpeechSegment segment = vad.front();
            vad.pop();
            segments.add(segment.getSamples());
        }
        return segments;
    }

    /** Ends the current utterance (for example when the speaker releases push-to-talk) and returns what is left. */
    public List<float[]> flush() {
        vad.flush();
        List<float[]> segments = takeSegments();
        vad.reset();
        return segments;
    }

    @Override
    public void close() {
        vad.release();
    }
}
