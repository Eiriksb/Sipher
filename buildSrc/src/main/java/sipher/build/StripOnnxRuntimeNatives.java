package sipher.build;

import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Removes the bundled native libraries from the ONNX Runtime Java jar.
 *
 * Sipher runs ONNX Runtime through the single libonnxruntime that ships with sherpa-onnx, plus a JNI glue library we
 * build against it, so the ~100 MB of natives in the official jar would be dead weight. Jars without native entries
 * pass through untouched, which keeps the transform safe to apply to a whole configuration.
 */
@DisableCachingByDefault(because = "Cheap local zip rewrite")
public abstract class StripOnnxRuntimeNatives implements TransformAction<TransformParameters.None> {
    private static final String NATIVE_PREFIX = "ai/onnxruntime/native/";

    @InputArtifact
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @Override
    public void transform(TransformOutputs outputs) {
        File input = getInputArtifact().get().getAsFile();
        if (!input.getName().endsWith(".jar") || !containsNatives(input)) {
            outputs.file(input);
            return;
        }

        File output = outputs.file(input.getName().replace(".jar", "-nonative.jar"));
        try (ZipFile zip = new ZipFile(input); ZipOutputStream out = new ZipOutputStream(new java.io.FileOutputStream(output))) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith(NATIVE_PREFIX)) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                out.putNextEntry(copy);
                if (!entry.isDirectory()) {
                    try (var in = zip.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                }
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean containsNatives(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            return zip.stream().anyMatch(entry -> entry.getName().startsWith(NATIVE_PREFIX));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
