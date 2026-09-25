package sipher.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads a build input (for example a bundled model file) and fails the build unless its SHA-256 matches.
 * The file is re-used when it already exists with the expected hash, so offline rebuilds work.
 */
@CacheableTask
public abstract class VerifiedDownload extends DefaultTask {
    @Input
    public abstract Property<String> getUrl();

    @Input
    public abstract Property<String> getSha256();

    @OutputFile
    public abstract RegularFileProperty getDestination();

    @TaskAction
    public void download() throws IOException, InterruptedException {
        Path target = getDestination().get().getAsFile().toPath();
        String expected = getSha256().get().toLowerCase();
        if (Files.isRegularFile(target) && expected.equals(sha256(target))) {
            return;
        }

        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(getUrl().get()))
                .header("User-Agent", "sipher-build")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new GradleException("Download of " + getUrl().get() + " failed with HTTP " + response.statusCode());
        }
        try (InputStream body = response.body()) {
            Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
        }

        String actual = sha256(partial);
        if (!expected.equals(actual)) {
            Files.deleteIfExists(partial);
            throw new GradleException("SHA-256 mismatch for " + getUrl().get() + ": expected " + expected + " but got " + actual);
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
