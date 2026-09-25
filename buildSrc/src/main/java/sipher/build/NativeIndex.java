package sipher.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.file.RegularFileProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Writes {@code index.txt} next to the packaged natives: one {@code <platform>/<file> <sha256> <size>} line per file.
 * The mod verifies every extracted native against this index before calling System.load.
 */
public abstract class NativeIndex extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getNativesDirectory();

    @OutputFile
    public abstract RegularFileProperty getIndexFile();

    @TaskAction
    public void write() throws IOException {
        Path root = getNativesDirectory().get().getAsFile().toPath();
        List<String> lines = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                lines.add(relative + " " + VerifiedDownload.sha256(file) + " " + Files.size(file));
            }
        }
        Path index = getIndexFile().get().getAsFile().toPath();
        Files.createDirectories(index.getParent());
        Files.write(index, lines);
    }
}
