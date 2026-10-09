package io.github.eiriksb.sipher.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every translation has exactly the keys of en_us, with the same placeholders in the same order. */
class TranslationFilesTest {
    private static final Path LANG = Path.of(System.getProperty("sipher.test.lang", "src/main/resources/assets/sipher/lang"));
    private static final Pattern PLACEHOLDER = Pattern.compile("%%|%s");

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static List<String> placeholders(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @Test
    void translationsMatchEnglish() throws IOException {
        JsonObject english = read(LANG.resolve("en_us.json"));
        List<Path> translations;
        try (Stream<Path> files = Files.list(LANG)) {
            translations = files.filter(file -> !file.getFileName().toString().equals("en_us.json")).sorted().toList();
        }
        assertTrue(translations.size() >= 15, "expected a translation for every language pack");

        for (Path file : translations) {
            JsonObject translation = read(file);
            assertEquals(english.keySet(), translation.keySet(), file + " keys");
            for (Map.Entry<String, JsonElement> entry : english.entrySet()) {
                String key = entry.getKey();
                assertEquals(placeholders(entry.getValue().getAsString()), placeholders(translation.get(key).getAsString()),
                        file + " " + key);
            }
        }
    }
}
