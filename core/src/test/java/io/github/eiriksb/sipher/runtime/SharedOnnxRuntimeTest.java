package io.github.eiriksb.sipher.runtime;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.github.eiriksb.sipher.models.BuiltinModels;
import io.github.eiriksb.sipher.testing.TestDirectories;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ONNX Runtime Java (used for translation) must run on the libonnxruntime that sherpa-onnx loaded, through the JNI
 * glue Sipher builds. The embedded ONNX Runtime jar has no natives of its own, so this fails if the sharing breaks.
 */
class SharedOnnxRuntimeTest {
    @Test
    void onnxRuntimeJavaRunsOnSherpasRuntime() throws Exception {
        NativeRuntime.Status natives = NativeRuntime.load(TestDirectories.sipher());
        TestDirectories.requireNatives(natives.ready(), "natives unavailable on this platform: " + natives.message());
        TestDirectories.requireNatives(natives.translationSupported(), "no ONNX Runtime JNI glue for this platform");

        OrtEnvironment environment = OrtEnvironment.getEnvironment();

        Path model = BuiltinModels.extract(TestDirectories.sipher()).sileroVad();
        try (OrtSession session = environment.createSession(model.toString(), new OrtSession.SessionOptions());
             OnnxTensor audio = OnnxTensor.createTensor(environment, FloatBuffer.wrap(new float[512]), new long[]{1, 512});
             OnnxTensor h = OnnxTensor.createTensor(environment, FloatBuffer.wrap(new float[128]), new long[]{2, 1, 64});
             OnnxTensor c = OnnxTensor.createTensor(environment, FloatBuffer.wrap(new float[128]), new long[]{2, 1, 64});
             OrtSession.Result result = session.run(Map.of("x", audio, "h", h, "c", c))) {
            float[][] speechProbability = (float[][]) result.get(0).getValue();
            assertEquals(1, speechProbability.length);
        }

        // On Linux we can check directly that only one ONNX Runtime is mapped into the process.
        Path maps = Path.of("/proc/self/maps");
        if (Files.isReadable(maps)) {
            Set<String> runtimes = Files.readAllLines(maps).stream()
                    .map(line -> line.substring(line.lastIndexOf(' ') + 1))
                    .filter(path -> path.contains("libonnxruntime") && !path.contains("4j_jni"))
                    .collect(Collectors.toSet());
            assertEquals(1, runtimes.size(), "expected exactly one libonnxruntime, got " + runtimes);
        }
    }
}
