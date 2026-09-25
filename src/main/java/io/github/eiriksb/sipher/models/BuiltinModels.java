package io.github.eiriksb.sipher.models;

import io.github.eiriksb.sipher.asr.AsrModel;
import io.github.eiriksb.sipher.runtime.BundledFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The models inside the Sipher jar: English speech recognition (Moonshine Tiny, MIT) and voice activity detection
 * (Silero VAD, MIT). They are copied to {@code <game>/sipher/models/builtin} on first use and verified on every start.
 */
public final class BuiltinModels {
    private static final String RESOURCE_ROOT = "sipher/models";

    private final Path root;

    private BuiltinModels(Path root) {
        this.root = root;
    }

    public static BuiltinModels extract(Path sipherDirectory) throws IOException {
        Path root = sipherDirectory.resolve("models").resolve("builtin").toAbsolutePath();
        BundledFiles.extract(RESOURCE_ROOT, BundledFiles.readIndex(RESOURCE_ROOT, ""), "", root);
        return new BuiltinModels(root);
    }

    public Path sileroVad() {
        return root.resolve("silero-vad").resolve("silero_vad.onnx");
    }

    public AsrModel englishSpeech() {
        return new AsrModel("moonshine-tiny-en", "moonshine", root.resolve("moonshine-tiny-en"), Map.of(
                "encoder", "encoder_model.ort",
                "merged_decoder", "decoder_model_merged.ort",
                "tokens", "tokens.txt"
        ), "en");
    }
}
