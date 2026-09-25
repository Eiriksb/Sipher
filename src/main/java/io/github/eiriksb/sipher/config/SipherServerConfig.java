package io.github.eiriksb.sipher.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-wide caption relay policy. Stored per world in {@code serverconfig/sipher-server.toml}. */
public final class SipherServerConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue RELAY_ENABLED;
    public static final ModConfigSpec.BooleanValue RELAY_PARTIALS;
    public static final ModConfigSpec.IntValue MAX_UPDATES_PER_SECOND;
    public static final ModConfigSpec.IntValue MAX_TEXT_LENGTH;
    public static final ModConfigSpec.DoubleValue FALLBACK_RANGE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
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

    private SipherServerConfig() {
    }
}
