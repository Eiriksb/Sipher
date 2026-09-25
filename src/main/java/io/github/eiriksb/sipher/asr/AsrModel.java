package io.github.eiriksb.sipher.asr;

import java.nio.file.Path;
import java.util.Map;

/**
 * A speech recognition model on disk.
 *
 * @param kind     sherpa-onnx model family: {@code moonshine}, {@code whisper}, {@code sense_voice}, {@code nemo_ctc},
 *                 {@code nemo_transducer} or {@code transducer}
 * @param files    role → file name inside {@code directory} (for example {@code encoder}, {@code tokens})
 * @param language language hint for multilingual models ({@code ""} lets the model detect it)
 */
public record AsrModel(String id, String kind, Path directory, Map<String, String> files, String language) {
    public String file(String role) {
        String name = files.get(role);
        if (name == null) {
            throw new IllegalArgumentException("Model " + id + " (" + kind + ") has no '" + role + "' file");
        }
        return directory.resolve(name).toString();
    }
}
