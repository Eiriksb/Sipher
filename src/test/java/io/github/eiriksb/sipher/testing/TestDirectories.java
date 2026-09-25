package io.github.eiriksb.sipher.testing;

import java.nio.file.Path;

public final class TestDirectories {
    private TestDirectories() {
    }

    /**
     * Stand-in for {@code <game>/sipher}. Shared by every test in the JVM because natives, once loaded, stay loaded
     * from where they were extracted — a per-class temporary directory would be deleted underneath them.
     */
    public static Path sipher() {
        return Path.of(System.getProperty("sipher.test.dir", "build/test-sipher")).toAbsolutePath();
    }
}
