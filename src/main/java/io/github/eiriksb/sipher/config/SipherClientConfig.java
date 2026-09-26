package io.github.eiriksb.sipher.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-player settings. Stored in {@code config/sipher-client.toml}; never synced to servers. */
public final class SipherClientConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue CAPTIONS_ENABLED;
    public static final ModConfigSpec.BooleanValue SHARE_MY_CAPTIONS;
    public static final ModConfigSpec.BooleanValue LIVE_PARTIALS;
    public static final ModConfigSpec.IntValue PARTIAL_INTERVAL_MS;
    public static final ModConfigSpec.ConfigValue<String> SPOKEN_LANGUAGE;
    public static final ModConfigSpec.ConfigValue<String> READING_LANGUAGE;
    public static final ModConfigSpec.BooleanValue WELCOME_SEEN;

    public static final ModConfigSpec.BooleanValue SHOW_OWN_BUBBLES;
    public static final ModConfigSpec.BooleanValue SHOW_OTHER_BUBBLES;
    public static final ModConfigSpec.DoubleValue BUBBLE_MAX_DISTANCE;
    public static final ModConfigSpec.DoubleValue BUBBLE_SCALE;
    public static final ModConfigSpec.IntValue BUBBLE_WRAP_WIDTH;
    public static final ModConfigSpec.IntValue BUBBLE_DISPLAY_MS;
    public static final ModConfigSpec.IntValue BUBBLE_FADE_MS;
    public static final ModConfigSpec.ConfigValue<String> BUBBLE_TEXT_COLOR;
    public static final ModConfigSpec.ConfigValue<String> BUBBLE_BACKGROUND_COLOR;
    public static final ModConfigSpec.DoubleValue BUBBLE_BACKGROUND_OPACITY;

    public static final ModConfigSpec.BooleanValue TRANSCRIPT_ENABLED;
    public static final ModConfigSpec.IntValue TRANSCRIPT_X;
    public static final ModConfigSpec.IntValue TRANSCRIPT_Y;
    public static final ModConfigSpec.IntValue TRANSCRIPT_WIDTH;
    public static final ModConfigSpec.IntValue TRANSCRIPT_LINES;

    public static final ModConfigSpec.IntValue RECOGNIZER_THREADS;
    public static final ModConfigSpec.BooleanValue ALLOW_DOWNLOADS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("captions");
        CAPTIONS_ENABLED = builder.comment("Transcribe your own voice chat audio into captions.")
                .define("enabled", true);
        SHARE_MY_CAPTIONS = builder.comment("Send your captions to players who can hear you (needs Sipher on the server). When off, captions stay on your screen.")
                .define("share_my_captions", true);
        LIVE_PARTIALS = builder.comment("Show live captions while you are still talking.")
                .define("live_partials", true);
        PARTIAL_INTERVAL_MS = builder.comment("How often live captions refresh while talking, in milliseconds.")
                .defineInRange("partial_interval_ms", 600, 250, 5000);
        SPOKEN_LANGUAGE = builder.comment("Language you speak (ISO 639-1 code). English works out of the box; other languages need their language pack.")
                .define("spoken_language", "en");
        READING_LANGUAGE = builder.comment("Language you want to read other players' captions in.")
                .define("reading_language", "en");
        WELCOME_SEEN = builder.comment("Whether the welcome screen has been shown. Your captions are never shared before it has. Modpacks may set this to true to skip it.")
                .define("welcome_seen", false);
        builder.pop();

        builder.push("bubbles");
        SHOW_OWN_BUBBLES = builder.comment("Show your own captions above your head in third person.")
                .define("show_own", true);
        SHOW_OTHER_BUBBLES = builder.comment("Show other players' captions above their heads.")
                .define("show_others", true);
        BUBBLE_MAX_DISTANCE = builder.comment("Hide caption bubbles for players further away than this many blocks.")
                .defineInRange("max_distance", 32.0, 4.0, 256.0);
        BUBBLE_SCALE = builder.comment("Caption bubble text scale.")
                .defineInRange("scale", 1.0, 0.5, 2.5);
        BUBBLE_WRAP_WIDTH = builder.comment("Caption bubble width in pixels before text wraps.")
                .defineInRange("wrap_width", 200, 100, 400);
        BUBBLE_DISPLAY_MS = builder.comment("How long a finished caption stays visible, in milliseconds.")
                .defineInRange("display_ms", 6000, 1000, 30000);
        BUBBLE_FADE_MS = builder.comment("How long captions take to fade out, in milliseconds.")
                .defineInRange("fade_ms", 1200, 0, 10000);
        BUBBLE_TEXT_COLOR = builder.comment("Caption text colour as RRGGBB.")
                .define("text_color", "FFFFFF");
        BUBBLE_BACKGROUND_COLOR = builder.comment("Caption background colour as RRGGBB.")
                .define("background_color", "000000");
        BUBBLE_BACKGROUND_OPACITY = builder.comment("Caption background opacity.")
                .defineInRange("background_opacity", 0.7, 0.0, 1.0);
        builder.pop();

        builder.push("transcript");
        TRANSCRIPT_ENABLED = builder.comment("Show a transcript box with recent captions. Drag it while chat is open.")
                .define("enabled", true);
        TRANSCRIPT_X = builder.defineInRange("x", 8, 0, 10000);
        TRANSCRIPT_Y = builder.defineInRange("y", 8, 0, 10000);
        TRANSCRIPT_WIDTH = builder.defineInRange("width", 240, 120, 600);
        TRANSCRIPT_LINES = builder.comment("Number of recent captions shown in the transcript box.")
                .defineInRange("lines", 6, 1, 20);
        builder.pop();

        builder.push("advanced");
        RECOGNIZER_THREADS = builder.comment("CPU threads used for speech recognition.")
                .defineInRange("recognizer_threads", 2, 1, 8);
        ALLOW_DOWNLOADS = builder.comment("Allow downloading optional language packs from the in-game Languages screen. Nothing is ever downloaded without you clicking Download.")
                .define("allow_downloads", true);
        builder.pop();

        SPEC = builder.build();
    }

    private SipherClientConfig() {
    }
}
