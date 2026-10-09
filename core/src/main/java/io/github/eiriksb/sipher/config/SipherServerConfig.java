package io.github.eiriksb.sipher.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Server-wide caption relay policy. Stored per world in {@code serverconfig/sipher-server.toml}. */
public final class SipherServerConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sipher");
    private static final String FILE = "sipher-server.toml";
    private static final long CHECK_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    public static final ConfigSpec SPEC;

    public static final ConfigSpec.BooleanValue RELAY_ENABLED;
    public static final ConfigSpec.BooleanValue RELAY_PARTIALS;
    public static final ConfigSpec.IntValue MAX_UPDATES_PER_SECOND;
    public static final ConfigSpec.IntValue MAX_TEXT_LENGTH;
    public static final ConfigSpec.DoubleValue FALLBACK_RANGE;

    static {
        ConfigSpec.Builder builder = new ConfigSpec.Builder();
        builder.push("relay");
        RELAY_ENABLED = builder.comment("Relay players' captions to the players who can hear them.")
                .define("enabled", true);
        RELAY_PARTIALS = builder.comment("Relay live (in-progress) captions, not only finished sentences.")
                .define("partials", true);
        MAX_UPDATES_PER_SECOND = builder.comment("Per-player rate limit for caption updates.")
                .defineInRange("max_updates_per_second", 8, 1, 40);
        MAX_TEXT_LENGTH = builder.comment("Longest caption text accepted from a client, in characters.")
                .defineInRange("max_text_length", 256, 32, 512);
        FALLBACK_RANGE = builder.comment("Caption range in blocks when Simple Voice Chat's server API is unavailable.")
                .defineInRange("fallback_range", 48.0, 1.0, 1024.0);
        builder.pop();
        SPEC = builder.build();
    }

    private static volatile long lastCheck;

    private SipherServerConfig() {
    }

    /**
     * Reads {@code <world>/serverconfig/sipher-server.toml} as the server starts. A new world gets a copy of
     * {@code <game>/defaultconfigs/sipher-server.toml} if a modpack ships one.
     */
    public static void load(Path worldDirectory, Path gameDirectory) {
        Path file = worldDirectory.resolve("serverconfig").resolve(FILE);
        Path defaults = gameDirectory.resolve("defaultconfigs").resolve(FILE);
        if (!Files.exists(file) && Files.isRegularFile(defaults)) {
            try {
                Files.createDirectories(file.getParent());
                Files.copy(defaults, file);
            } catch (IOException e) {
                LOGGER.warn("Could not copy {} into the world", defaults, e);
            }
        }
        SPEC.load(file);
        lastCheck = System.nanoTime();
    }

    /** Picks up edits to the file while the server runs; checks the disk at most every few seconds. Server thread. */
    public static void refresh() {
        long now = System.nanoTime();
        if (now - lastCheck >= CHECK_INTERVAL_NANOS) {
            lastCheck = now;
            SPEC.reloadIfChanged();
        }
    }
}
