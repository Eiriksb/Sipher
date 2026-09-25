package io.github.eiriksb.sipher.models;

import io.github.eiriksb.sipher.Sipher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Installed state of the optional language packs, and the downloads the player starts from the Languages screen.
 *
 * <p>Components live in {@code <game>/sipher/models/<component>/}. A pack can also be installed by hand: put its files
 * there and press Download — files that are already present with the right checksum are not downloaded again.
 */
public final class LanguagePacks {
    public enum State { BUILT_IN, INSTALLED, AVAILABLE, DOWNLOADING, FAILED }

    public record Status(State state, long bytesDone, long bytesTotal, String error) {
        public float progress() {
            return bytesTotal == 0 ? 0f : (float) bytesDone / bytesTotal;
        }
    }

    private static final class Job {
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile long done;
        volatile long total;
    }

    private final Catalog catalog;
    private final Path root;
    private final ModelDownloader downloader;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();

    public LanguagePacks(Catalog catalog, Path sipherDirectory, String userAgent) {
        this.catalog = catalog;
        this.root = sipherDirectory.resolve("models").toAbsolutePath();
        this.downloader = new ModelDownloader(ModelDownloader.Policy.production(catalog.hosts()), userAgent);
    }

    public Catalog catalog() {
        return catalog;
    }

    public Path directory(String componentId) {
        return root.resolve(componentId);
    }

    public boolean installed(String componentId) {
        return ModelDownloader.isInstalled(directory(componentId), catalog.component(componentId).files());
    }

    public boolean installed(Catalog.Language language) {
        return language.components().stream().allMatch(this::installed);
    }

    public Status status(Catalog.Language language) {
        if (language.builtIn()) {
            return new Status(State.BUILT_IN, 0, 0, null);
        }
        Job job = jobs.get(language.code());
        if (job != null) {
            return new Status(State.DOWNLOADING, job.done, job.total, null);
        }
        if (installed(language)) {
            return new Status(State.INSTALLED, 0, 0, null);
        }
        String error = failures.get(language.code());
        return new Status(error == null ? State.AVAILABLE : State.FAILED, 0, 0, error);
    }

    /** Bytes still to download for a language (components shared with an installed language are not counted). */
    public long remainingBytes(Catalog.Language language) {
        return language.components().stream().distinct().filter(id -> !installed(id))
                .mapToLong(id -> catalog.component(id).size()).sum();
    }

    /** Starts downloading every missing component of a language on a background thread. */
    public void install(Catalog.Language language, Consumer<Catalog.Language> onInstalled) {
        Job job = new Job();
        if (jobs.putIfAbsent(language.code(), job) != null) {
            return;
        }
        failures.remove(language.code());
        job.total = remainingBytes(language);
        Thread thread = new Thread(() -> {
            try {
                long before = 0;
                for (String id : language.components().stream().distinct().toList()) {
                    if (installed(id)) {
                        continue;
                    }
                    Catalog.Component component = catalog.component(id);
                    long offset = before;
                    downloader.install(directory(id), component.files(), (done, total) -> job.done = offset + done, job.cancelled);
                    before += component.size();
                }
                Sipher.LOGGER.info("Installed Sipher language pack {}", language.code());
                onInstalled.accept(language);
            } catch (Exception e) {
                if (!job.cancelled.get()) {
                    Sipher.LOGGER.warn("Could not install Sipher language pack {}", language.code(), e);
                    failures.put(language.code(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            } finally {
                jobs.remove(language.code());
            }
        }, "Sipher download " + language.code());
        thread.setDaemon(true);
        thread.start();
    }

    public void cancel(Catalog.Language language) {
        Job job = jobs.get(language.code());
        if (job != null) {
            job.cancelled.set(true);
        }
    }

    /** Deletes a language's components, keeping any that another installed language still uses. */
    public void delete(Catalog.Language language) throws IOException {
        for (String id : language.components()) {
            boolean shared = catalog.languages().stream()
                    .anyMatch(other -> other != language && other.components().contains(id) && installed(other));
            if (!shared) {
                deleteRecursively(directory(id));
            }
        }
        failures.remove(language.code());
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
