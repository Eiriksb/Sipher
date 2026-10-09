package io.github.eiriksb.sipher.asr;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;

/** Offline (utterance-at-a-time) speech recognition through sherpa-onnx. */
public final class SpeechRecognizer implements AutoCloseable {
    public static final int SAMPLE_RATE = 16_000;

    private final AsrModel model;
    private final OfflineRecognizer recognizer;

    private SpeechRecognizer(AsrModel model, OfflineRecognizer recognizer) {
        this.model = model;
        this.recognizer = recognizer;
    }

    public static SpeechRecognizer create(AsrModel model, int threads) {
        OfflineModelConfig.Builder config = OfflineModelConfig.builder()
                .setTokens(model.file("tokens"))
                .setNumThreads(Math.max(1, threads))
                .setDebug(false);

        switch (model.kind()) {
            case "moonshine" -> config.setMoonshine(OfflineMoonshineModelConfig.builder()
                    .setEncoder(model.file("encoder"))
                    .setMergedDecoder(model.file("merged_decoder"))
                    .build());
            case "whisper" -> config.setWhisper(OfflineWhisperModelConfig.builder()
                    .setEncoder(model.file("encoder"))
                    .setDecoder(model.file("decoder"))
                    .setLanguage(model.language())
                    .setTask("transcribe")
                    .build());
            case "sense_voice" -> config.setSenseVoice(OfflineSenseVoiceModelConfig.builder()
                    .setModel(model.file("model"))
                    .setLanguage(model.language())
                    .setInverseTextNormalization(true)
                    .build());
            case "nemo_ctc" -> config.setNemo(OfflineNemoEncDecCtcModelConfig.builder()
                    .setModel(model.file("model"))
                    .build());
            case "nemo_transducer", "transducer" -> {
                config.setTransducer(OfflineTransducerModelConfig.builder()
                        .setEncoder(model.file("encoder"))
                        .setDecoder(model.file("decoder"))
                        .setJoiner(model.file("joiner"))
                        .build());
                if (model.kind().equals("nemo_transducer")) {
                    config.setModelType("nemo_transducer");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported speech model kind: " + model.kind());
        }

        OfflineRecognizer recognizer = new OfflineRecognizer(OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(config.build())
                .setDecodingMethod("greedy_search")
                .build());
        return new SpeechRecognizer(model, recognizer);
    }

    public AsrModel model() {
        return model;
    }

    /** Transcribes 16 kHz mono audio in [-1, 1]. */
    public synchronized String transcribe(float[] samples) {
        if (samples.length == 0) {
            return "";
        }
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE);
            recognizer.decode(stream);
            return recognizer.getResult(stream).getText().trim();
        } finally {
            stream.release();
        }
    }

    @Override
    public synchronized void close() {
        recognizer.release();
    }
}
