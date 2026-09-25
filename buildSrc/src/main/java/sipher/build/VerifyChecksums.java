package sipher.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Fails the build unless every file in {@link #getFiles()} has the SHA-256 recorded for its name in
 * {@link #getChecksums()} ({@code <file name> <sha256>} per line, {@code #} comments allowed).
 *
 * Used for the third-party jars whose code or natives end up inside the Sipher jar, so what ships is exactly what was
 * reviewed even though sherpa-onnx is fetched from GitHub releases rather than a signed Maven repository.
 */
public abstract class VerifyChecksums extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getFiles();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getChecksums();

    @TaskAction
    public void verify() throws IOException {
        Map<String, String> expected = new HashMap<>();
        for (String line : Files.readAllLines(getChecksums().get().getAsFile().toPath())) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            expected.put(parts[0], parts[1].toLowerCase());
        }
        for (File file : getFiles()) {
            String want = expected.get(file.getName());
            if (want == null) {
                throw new GradleException("No pinned checksum for " + file.getName() + " in " + getChecksums().get());
            }
            String actual = VerifiedDownload.sha256(file.toPath());
            if (!want.equals(actual)) {
                throw new GradleException("Checksum mismatch for " + file.getName() + ": expected " + want + ", got " + actual);
            }
        }
    }
}
