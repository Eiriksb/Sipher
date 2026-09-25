package io.github.eiriksb.sipher.models;

import io.github.eiriksb.sipher.runtime.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads the files of one model component, only ever when the player asked for it.
 *
 * <p>Safety rules, enforced here rather than trusted to the catalogue:
 * <ul>
 *     <li>HTTPS only, and every redirect hop must stay on an allowlisted host.</li>
 *     <li>Only data files (model weights, vocabularies, licences) — never code or native libraries.</li>
 *     <li>Each file is streamed to {@code .part}, capped at its expected size, checked against its SHA-256, and only
 *         then moved into place. A component counts as installed only once every file has been verified.</li>
 *     <li>Paths cannot escape the component directory.</li>
 * </ul>
 */
public final class ModelDownloader {
    /** File types a model pack may contain. Anything else is refused, whatever the catalogue says. */
    private static final Set<String> DATA_EXTENSIONS = Set.of(".onnx", ".ort", ".txt", ".json", ".spm", ".model", ".md");
    private static final Set<String> DATA_FILE_NAMES = Set.of("LICENSE", "NOTICE", "README");
    private static final int MAX_REDIRECTS = 5;
    static final String INSTALLED_MARKER = ".installed";

    public record Policy(Set<String> allowedHosts, boolean allowPlainHttp) {
        public static Policy production(Set<String> hosts) {
            return new Policy(hosts, false);
        }
    }

    public record FileSpec(String path, String url, String sha256, long size) {
    }

    public interface Progress {
        void update(long bytesDone, long bytesTotal);
    }

    public static final class DownloadException extends IOException {
        public DownloadException(String message) {
            super(message);
        }
    }

    private final Policy policy;
    private final HttpClient client;
    private final String userAgent;

    public ModelDownloader(Policy policy, String userAgent) {
        this.policy = policy;
        this.userAgent = userAgent;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** Downloads (or resumes) every file into {@code directory}, then marks the component installed. */
    public void install(Path directory, List<FileSpec> files, Progress progress, AtomicBoolean cancelled)
            throws IOException, InterruptedException {
        long total = files.stream().mapToLong(FileSpec::size).sum();
        long done = 0;
        Files.createDirectories(directory);
        Files.deleteIfExists(directory.resolve(INSTALLED_MARKER));
        for (FileSpec file : files) {
            Path target = resolveSafely(directory, file.path());
            if (!(Files.isRegularFile(target) && Files.size(target) == file.size() && file.sha256().equals(Hashing.sha256(target)))) {
                download(file, target, done, total, progress, cancelled);
            }
            done += file.size();
            progress.update(done, total);
        }
        Files.writeString(directory.resolve(INSTALLED_MARKER), String.valueOf(System.currentTimeMillis()));
    }

    /** True when {@link #install} completed and every file still has its expected size. */
    public static boolean isInstalled(Path directory, List<FileSpec> files) {
        if (!Files.isRegularFile(directory.resolve(INSTALLED_MARKER))) {
            return false;
        }
        try {
            for (FileSpec file : files) {
                Path path = resolveSafely(directory, file.path());
                if (!Files.isRegularFile(path) || Files.size(path) != file.size()) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void download(FileSpec file, Path target, long doneBefore, long total, Progress progress, AtomicBoolean cancelled)
            throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        long resumeFrom = Files.isRegularFile(partial) ? Files.size(partial) : 0;
        if (resumeFrom >= file.size()) {
            Files.delete(partial);
            resumeFrom = 0;
        }

        HttpResponse<InputStream> response = open(URI.create(file.url()), resumeFrom);
        boolean resumed = response.statusCode() == 206;
        if (!resumed && response.statusCode() != 200) {
            response.body().close();
            throw new DownloadException("HTTP " + response.statusCode() + " for " + file.path());
        }

        MessageDigest digest = Hashing.newSha256();
        long written = 0;
        if (resumed) {
            // Re-hash what we already have so the final check covers the whole file.
            try (InputStream existing = Files.newInputStream(partial)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = existing.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                    written += read;
                }
            }
        }

        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(partial, resumed
                     ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                     : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE})) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (cancelled.get()) {
                    throw new DownloadException("Cancelled");
                }
                written += read;
                if (written > file.size()) {
                    out.close();
                    Files.deleteIfExists(partial);
                    throw new DownloadException(file.path() + " is larger than expected");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                progress.update(doneBefore + written, total);
            }
        }

        String actual = HexFormat.of().formatHex(digest.digest());
        if (written != file.size() || !actual.equals(file.sha256())) {
            Files.deleteIfExists(partial);
            throw new DownloadException(file.path() + " failed verification (size " + written + ", sha256 " + actual + ")");
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private HttpResponse<InputStream> open(URI uri, long resumeFrom) throws IOException, InterruptedException {
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkAllowed(uri);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", userAgent)
                    .GET();
            if (resumeFrom > 0) {
                request.header("Range", "bytes=" + resumeFrom + "-");
            }
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String host = uri.getHost();
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new DownloadException("Redirect without Location from " + host));
                response.body().close();
                uri = uri.resolve(location);
                continue;
            }
            return response;
        }
        throw new DownloadException("Too many redirects");
    }

    private void checkAllowed(URI uri) throws DownloadException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(policy.allowPlainHttp() && scheme.equals("http"))) {
            throw new DownloadException("Refusing non-HTTPS download: " + uri);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!policy.allowedHosts().contains(host)) {
            throw new DownloadException("Refusing download from unlisted host " + host);
        }
    }

    static Path resolveSafely(Path directory, String relative) throws DownloadException {
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        if (!DATA_EXTENSIONS.contains(extension) && !DATA_FILE_NAMES.contains(name)) {
            throw new DownloadException("Refusing non-data file " + relative);
        }
        Path root = directory.toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new DownloadException("Refusing path outside the model directory: " + relative);
        }
        return target;
    }
}
