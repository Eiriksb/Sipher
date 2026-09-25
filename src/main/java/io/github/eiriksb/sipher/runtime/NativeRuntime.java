package io.github.eiriksb.sipher.runtime;

import io.github.eiriksb.sipher.Sipher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Extracts and loads the native libraries that ship inside the Sipher jar.
 *
 * <p>One {@code libonnxruntime} (the one bundled with sherpa-onnx) serves both speech recognition (sherpa-onnx) and
 * text translation (ONNX Runtime Java). The JVM refuses to {@code System.load} one file from two class loaders, so
 * only sherpa's loader loads it; ONNX Runtime Java is told to skip it and only loads its JNI glue, which Sipher builds
 * against sherpa's runtime and which the dynamic linker binds to the already-loaded library.
 *
 * <p>Nothing here is ever downloaded: every native comes from the jar and is verified against the build-time index.
 */
public final class NativeRuntime {
    private static final String RESOURCE_ROOT = "sipher/natives";
    private static final String ONNXRUNTIME = "onnxruntime";
    private static final String SHERPA_JNI = "sherpa-onnx-jni";
    private static final String ORT_JNI = "onnxruntime4j_jni";

    public enum State { NOT_LOADED, READY, UNSUPPORTED, FAILED }

    public record Status(State state, boolean translationSupported, String message) {
        public boolean ready() {
            return state == State.READY;
        }
    }

    private static volatile Status status = new Status(State.NOT_LOADED, false, "Not loaded");
    private static Path directory;
    private static List<BundledFiles.Entry> entries;
    private static NativePlatform platform;

    private NativeRuntime() {
    }

    public static Status status() {
        return status;
    }

    /**
     * Points the sherpa-onnx and ONNX Runtime loaders at Sipher's native directory. Cheap and safe to call early (for
     * example from the mod constructor) so the properties are in place before any other code touches either library.
     */
    public static synchronized void configure(Path sipherDirectory) {
        if (directory != null || status.state() == State.UNSUPPORTED) {
            return;
        }
        Optional<NativePlatform> current = NativePlatform.current();
        if (current.isEmpty()) {
            status = new Status(State.UNSUPPORTED, false,
                    "Unsupported platform " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            return;
        }
        platform = current.get();
        try {
            entries = BundledFiles.readIndex(RESOURCE_ROOT, platform.id() + "/");
        } catch (IOException e) {
            status = new Status(State.FAILED, false, "Native index unreadable: " + e.getMessage());
            return;
        }
        if (!has(ONNXRUNTIME) || !has(SHERPA_JNI)) {
            status = new Status(State.UNSUPPORTED, false, "No natives bundled for " + platform.id());
            return;
        }

        // Versioned by content so an update never loads a stale library and never overwrites one that is in use.
        directory = sipherDirectory.resolve("natives").resolve(platform.id() + "-" + contentHash(entries)).toAbsolutePath();
        System.setProperty("sherpa_onnx.native.path", directory.toString());
        if (has(ORT_JNI)) {
            System.setProperty("onnxruntime.native.path", directory.toString());
            System.setProperty("onnxruntime.native." + ONNXRUNTIME + ".skip", "true");
        }
    }

    /** Extracts (if needed) and loads the natives. Blocking; call from a background thread. */
    public static synchronized Status load(Path sipherDirectory) {
        if (status.state() != State.NOT_LOADED) {
            return status;
        }
        configure(sipherDirectory);
        if (directory == null) {
            if (status.state() == State.NOT_LOADED) {
                status = new Status(State.FAILED, false, "Natives were not configured");
            }
            return status;
        }

        try {
            BundledFiles.extract(RESOURCE_ROOT, entries, platform.id() + "/", directory);
            // Loads libonnxruntime and the sherpa JNI library from sherpa_onnx.native.path, in sherpa's class loader.
            com.k2fsa.sherpa.onnx.LibraryUtils.load();
            com.k2fsa.sherpa.onnx.LibraryLoader.setAutoLoadEnabled(false);
            boolean translation = has(ORT_JNI);
            status = new Status(State.READY, translation,
                    translation ? "Ready (" + platform.id() + ")" : "Ready (" + platform.id() + ", translation unavailable)");
            Sipher.LOGGER.info("Loaded Sipher natives for {} from {}", platform.id(), directory);
        } catch (IOException | LinkageError | RuntimeException e) {
            status = new Status(State.FAILED, false, "Could not load natives: " + e.getMessage());
            Sipher.LOGGER.error("Could not load Sipher natives for {}", platform.id(), e);
        }
        return status;
    }

    private static boolean has(String library) {
        String file = platform.id() + "/" + platform.libraryFile(library);
        return entries.stream().anyMatch(entry -> entry.path().equals(file));
    }

    private static String contentHash(List<BundledFiles.Entry> entries) {
        var digest = Hashing.newSha256();
        for (BundledFiles.Entry entry : entries) {
            digest.update(entry.sha256().getBytes(StandardCharsets.US_ASCII));
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
    }
}
