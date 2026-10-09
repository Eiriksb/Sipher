package io.github.eiriksb.sipher.testing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

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

    /**
     * Skips the test when natives are unavailable — unless {@code SIPHER_REQUIRE_NATIVES} is set (as in CI's
     * per-platform jobs), where a platform that cannot load them must fail loudly instead of passing as skipped.
     */
    public static void requireNatives(boolean available, String message) {
        if (System.getenv("SIPHER_REQUIRE_NATIVES") != null) {
            Assertions.assertTrue(available, message);
        } else {
            Assumptions.assumeTrue(available, message);
        }
    }
}
