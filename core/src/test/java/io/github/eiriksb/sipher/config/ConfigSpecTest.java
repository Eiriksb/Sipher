package io.github.eiriksb.sipher.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSpecTest {
    private final ConfigSpec spec;
    private final ConfigSpec.BooleanValue enabled;
    private final ConfigSpec.IntValue interval;
    private final ConfigSpec.DoubleValue distance;
    private final ConfigSpec.ConfigValue<String> language;

    ConfigSpecTest() {
        ConfigSpec.Builder builder = new ConfigSpec.Builder();
        builder.push("captions");
        enabled = builder.comment("Transcribe your own voice chat audio into captions.").define("enabled", true);
        interval = builder.defineInRange("partial_interval_ms", 600, 250, 5000);
        language = builder.define("spoken_language", "en");
        builder.pop();
        builder.push("bubbles");
        distance = builder.defineInRange("max_distance", 32.0, 4.0, 256.0);
        builder.pop();
        spec = builder.build();
    }

    @Test
    void readsWhatNeoForgeWroteAndLeavesItAlone(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("sipher-client.toml");
        String neoforge = """
                [captions]
                \t#Transcribe your own voice chat audio into captions.
                \tenabled = false
                \t# Default: 600
                \t# Range: 250 ~ 5000
                \tpartial_interval_ms = 900
                \tspoken_language = "de"

                [bubbles]
                \t# Range: 4.0 ~ 256.0
                \tmax_distance = 64.0
                """;
        Files.writeString(file, neoforge);

        spec.load(file);

        assertFalse(enabled.get());
        assertEquals(900, interval.get());
        assertEquals("de", language.get());
        assertEquals(64.0, distance.get());
        assertEquals(neoforge, Files.readString(file), "valid files are not rewritten");
    }

    @Test
    void correctsBadValuesAndWritesThemBack(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("sipher-client.toml");
        Files.writeString(file, """
                [captions]
                enabled = "yes"
                partial_interval_ms = 99999
                [bubbles]
                max_distance = 2
                """);

        spec.load(file);

        assertTrue(enabled.get(), "wrong type falls back to the default");
        assertEquals(5000, interval.get(), "out of range is clamped");
        assertEquals(4.0, distance.get());
        assertEquals("en", language.get(), "missing falls back to the default");
        Map<String, Object> written = ConfigSpec.parse(Files.readAllLines(file));
        assertEquals(Map.of("captions.enabled", true, "captions.partial_interval_ms", 5000L,
                "captions.spoken_language", "en", "bubbles.max_distance", 4.0), written);
    }

    @Test
    void savesChangesAndCreatesMissingFiles(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("config").resolve("sipher-client.toml");
        spec.load(file);
        assertTrue(Files.exists(file));

        language.set("say \"hi\"\\\n");
        interval.set(10);
        spec.save();

        Map<String, Object> written = ConfigSpec.parse(Files.readAllLines(file));
        assertEquals("say \"hi\"\\\n", written.get("captions.spoken_language"));
        assertEquals(250L, written.get("captions.partial_interval_ms"));
        assertFalse(Files.exists(file.resolveSibling("sipher-client.toml.tmp")));
    }

    @Test
    void reloadsWhenTheFileChanges(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("sipher-server.toml");
        spec.load(file);
        spec.reloadIfChanged();
        assertTrue(enabled.get());

        Files.writeString(file, "[captions]\nenabled = false\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(10)));
        spec.reloadIfChanged();

        assertFalse(enabled.get());
    }

    @Test
    void parsesTomlDetails() {
        Map<String, Object> parsed = ConfigSpec.parse(List.of(
                "# comment",
                "[relay]",
                "a = 'literal \\n'",
                "b = \"\\u00e6 # not a comment\" # comment",
                "c = +1_000",
                "d = -2.5e3",
                "broken = \"unterminated",
                "[next]",
                "\"e\" = true"));
        assertEquals(Map.of("relay.a", "literal \\n", "relay.b", "æ # not a comment", "relay.c", 1000L, "relay.d", -2500.0,
                "next.e", true), parsed);
    }
}
