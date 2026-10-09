package io.github.eiriksb.sipher.models;

import com.sun.net.httpserver.HttpServer;
import io.github.eiriksb.sipher.runtime.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDownloaderTest {
    private static final byte[] MODEL = "pretend these are model weights".repeat(1000).getBytes(StandardCharsets.UTF_8);
    private static final byte[] VOCAB = "{\"<unk>\": 1}".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    private HttpServer server;
    private final Map<String, byte[]> served = new HashMap<>();
    private final Map<String, String> redirects = new HashMap<>();
    private final AtomicInteger rangeRequests = new AtomicInteger();
    private String base;
    private ModelDownloader downloader;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (redirects.containsKey(path)) {
                exchange.getResponseHeaders().add("Location", redirects.get(path));
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            byte[] body = served.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            int from = 0;
            int status = 200;
            if (range != null) {
                rangeRequests.incrementAndGet();
                from = Integer.parseInt(range.replaceAll("bytes=(\\d+)-", "$1"));
                status = 206;
            }
            exchange.sendResponseHeaders(status, body.length - from);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body, from, body.length - from);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        downloader = new ModelDownloader(new ModelDownloader.Policy(Set.of("127.0.0.1"), true), "sipher-test");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void installsAndVerifiesFiles() throws Exception {
        served.put("/m/encoder.onnx", MODEL);
        served.put("/m/vocab.json", VOCAB);
        List<ModelDownloader.FileSpec> files = List.of(spec("encoder.onnx", "/m/encoder.onnx", MODEL), spec("vocab.json", "/m/vocab.json", VOCAB));

        AtomicInteger updates = new AtomicInteger();
        downloader.install(directory, files, (done, total) -> updates.incrementAndGet(), new AtomicBoolean());

        assertArrayEquals(MODEL, Files.readAllBytes(directory.resolve("encoder.onnx")));
        assertTrue(ModelDownloader.isInstalled(directory, files));
        assertTrue(updates.get() > 0);
    }

    @Test
    void rejectsTamperedContent() {
        served.put("/m/encoder.onnx", "something else entirely".getBytes(StandardCharsets.UTF_8));
        List<ModelDownloader.FileSpec> files = List.of(new ModelDownloader.FileSpec("encoder.onnx", base + "/m/encoder.onnx", sha256(MODEL), 23));

        assertThrows(ModelDownloader.DownloadException.class, () -> install(files));
        assertFalse(Files.exists(directory.resolve("encoder.onnx")));
        assertFalse(ModelDownloader.isInstalled(directory, files));
    }

    @Test
    void stopsReadingWhenAServerSendsMoreThanExpected() {
        served.put("/m/encoder.onnx", MODEL);
        List<ModelDownloader.FileSpec> files = List.of(new ModelDownloader.FileSpec("encoder.onnx", base + "/m/encoder.onnx", sha256(MODEL), 100));

        var error = assertThrows(ModelDownloader.DownloadException.class, () -> install(files));
        assertTrue(error.getMessage().contains("larger than expected"), error.getMessage());
        assertFalse(Files.exists(directory.resolve("encoder.onnx.part")));
    }

    @Test
    void refusesRedirectsToUnlistedHosts() {
        redirects.put("/m/encoder.onnx", "https://attacker.example/payload.onnx");
        var error = assertThrows(ModelDownloader.DownloadException.class,
                () -> install(List.of(spec("encoder.onnx", "/m/encoder.onnx", MODEL))));
        assertTrue(error.getMessage().contains("unlisted host attacker.example"), error.getMessage());
    }

    @Test
    void followsRedirectsWithinAllowedHosts() throws Exception {
        served.put("/cdn/blob", MODEL);
        redirects.put("/m/encoder.onnx", "/cdn/blob");
        install(List.of(spec("encoder.onnx", "/m/encoder.onnx", MODEL)));
        assertArrayEquals(MODEL, Files.readAllBytes(directory.resolve("encoder.onnx")));
    }

    @Test
    void refusesCodeAndPathsOutsideTheModelDirectory() {
        for (String path : List.of("libevil.so", "evil.dll", "Mod.jar", "run.sh", "../escape.onnx", "a/../../escape.json")) {
            served.put("/x", MODEL);
            assertThrows(ModelDownloader.DownloadException.class,
                    () -> install(List.of(new ModelDownloader.FileSpec(path, base + "/x", sha256(MODEL), MODEL.length))), path);
        }
    }

    @Test
    void productionPolicyRequiresHttps() {
        ModelDownloader production = new ModelDownloader(ModelDownloader.Policy.production(Set.of("127.0.0.1")), "sipher-test");
        served.put("/m/encoder.onnx", MODEL);
        assertThrows(ModelDownloader.DownloadException.class, () -> production.install(directory,
                List.of(spec("encoder.onnx", "/m/encoder.onnx", MODEL)), (a, b) -> {
                }, new AtomicBoolean()));
    }

    @Test
    void resumesAPartialDownload() throws Exception {
        served.put("/m/encoder.onnx", MODEL);
        Files.write(directory.resolve("encoder.onnx.part"), java.util.Arrays.copyOf(MODEL, MODEL.length / 2));

        install(List.of(spec("encoder.onnx", "/m/encoder.onnx", MODEL)));

        assertEquals(1, rangeRequests.get());
        assertArrayEquals(MODEL, Files.readAllBytes(directory.resolve("encoder.onnx")));
    }

    @Test
    void canBeCancelled() {
        served.put("/m/encoder.onnx", MODEL);
        AtomicBoolean cancelled = new AtomicBoolean(true);
        assertThrows(ModelDownloader.DownloadException.class, () -> downloader.install(directory,
                List.of(spec("encoder.onnx", "/m/encoder.onnx", MODEL)), (a, b) -> {
                }, cancelled));
        assertFalse(Files.exists(directory.resolve("encoder.onnx")));
    }

    private void install(List<ModelDownloader.FileSpec> files) throws Exception {
        downloader.install(directory, files, (done, total) -> {
        }, new AtomicBoolean());
    }

    private ModelDownloader.FileSpec spec(String path, String urlPath, byte[] content) {
        return new ModelDownloader.FileSpec(path, base + urlPath, sha256(content), content.length);
    }

    private static String sha256(byte[] content) {
        return HexFormat.of().formatHex(Hashing.newSha256().digest(content));
    }
}
