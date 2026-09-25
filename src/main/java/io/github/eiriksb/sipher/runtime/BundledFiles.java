package io.github.eiriksb.sipher.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies files that ship inside the mod jar (natives, built-in models) into the game directory.
 *
 * Every bundle has an {@code index.txt} generated at build time with one {@code <path> <sha256> <size>} line per
 * file. Files are only extracted when missing or different, always through a temporary file, and are verified
 * before they replace anything, so a half-written file is never loaded.
 */
public final class BundledFiles {
    private BundledFiles() {
    }

    public record Entry(String path, String sha256, long size) {
    }

    /** Reads the index of a bundle, keeping only the entries under {@code prefix} (for example a platform id). */
    public static List<Entry> readIndex(String resourceRoot, String prefix) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (InputStream in = open(resourceRoot + "/index.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 3 || !parts[0].startsWith(prefix)) {
                    continue;
                }
                entries.add(new Entry(parts[0], parts[1], Long.parseLong(parts[2])));
            }
        }
        return entries;
    }

    /** Extracts {@code entries} from {@code resourceRoot} into {@code targetRoot}, stripping {@code stripPrefix} from each path. */
    public static void extract(String resourceRoot, List<Entry> entries, String stripPrefix, Path targetRoot) throws IOException {
        Files.createDirectories(targetRoot);
        for (Entry entry : entries) {
            String relative = entry.path().substring(stripPrefix.length());
            Path target = targetRoot.resolve(relative).normalize();
            if (!target.startsWith(targetRoot)) {
                throw new IOException("Refusing to extract outside " + targetRoot + ": " + entry.path());
            }
            if (Files.isRegularFile(target) && Files.size(target) == entry.size() && entry.sha256().equals(Hashing.sha256(target))) {
                continue;
            }

            Files.createDirectories(target.getParent());
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            try (InputStream in = open(resourceRoot + "/" + entry.path())) {
                Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            String actual = Hashing.sha256(partial);
            if (!entry.sha256().equals(actual)) {
                Files.deleteIfExists(partial);
                throw new IOException("Bundled file " + entry.path() + " is corrupt (sha256 " + actual + ")");
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static InputStream open(String resource) throws IOException {
        InputStream in = BundledFiles.class.getResourceAsStream("/" + resource);
        if (in == null) {
            throw new IOException("Missing bundled resource " + resource);
        }
        return in;
    }
}
