package io.github.eiriksb.sipher.models;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eiriksb.sipher.asr.AsrModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The language packs Sipher can download, compiled into the jar ({@code sipher/catalog.json}). There is deliberately
 * no remote catalogue: which files can be downloaded, from where, and their checksums are fixed per mod version.
 */
public final class Catalog {
    private static final String RESOURCE = "/sipher/catalog.json";

    public record Component(String id, String type, long size, String license, String attribution,
                            List<ModelDownloader.FileSpec> files, JsonObject details) {
        public boolean isSpeech() {
            return type.equals("speech");
        }

        /** Speech component as a recogniser model; {@code language} is the hint for multilingual models. */
        public AsrModel asrModel(Path directory, String language) {
            Map<String, String> roles = new LinkedHashMap<>();
            details.getAsJsonObject("roles").entrySet().forEach(e -> roles.put(e.getKey(), e.getValue().getAsString()));
            String hint = details.has("languageHint") && !details.get("languageHint").getAsString().isEmpty()
                    ? details.get("languageHint").getAsString() : language;
            if (details.has("languageHints") && details.getAsJsonObject("languageHints").has(language)) {
                hint = details.getAsJsonObject("languageHints").get(language).getAsString();
            }
            return new AsrModel(id, details.get("asrKind").getAsString(), directory, roles, hint);
        }

        /** Target language token for multilingual translation models, or {@code null}. */
        public String targetToken() {
            JsonElement token = details.get("targetToken");
            return token == null || token.isJsonNull() ? null : token.getAsString();
        }
    }

    /**
     * One language as the player sees it.
     *
     * @param speech      speech recognition component, or {@code null} when captions are built in (English)
     * @param toEnglish   translation component X→en ({@code null} for English)
     * @param fromEnglish translation component en→X ({@code null} for English)
     */
    public record Language(String code, String name, String englishName, String speech, String toEnglish, String fromEnglish) {
        public boolean builtIn() {
            return speech == null && toEnglish == null && fromEnglish == null;
        }

        public List<String> components() {
            List<String> ids = new ArrayList<>();
            for (String id : new String[]{speech, toEnglish, fromEnglish}) {
                if (id != null) {
                    ids.add(id);
                }
            }
            return ids;
        }
    }

    private final Set<String> hosts;
    private final Map<String, Component> components;
    private final List<Language> languages;

    private Catalog(Set<String> hosts, Map<String, Component> components, List<Language> languages) {
        this.hosts = hosts;
        this.components = components;
        this.languages = languages;
    }

    public static Catalog load() throws IOException {
        try (InputStream in = Catalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing " + RESOURCE);
            }
            return parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        }
    }

    public static Catalog empty() {
        return new Catalog(Set.of(), Map.of(), List.of());
    }

    static Catalog parse(JsonObject root) {
        Set<String> hosts = new LinkedHashSet<>();
        root.getAsJsonArray("hosts").forEach(host -> hosts.add(host.getAsString()));

        Map<String, Component> components = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("components").entrySet()) {
            JsonObject json = entry.getValue().getAsJsonObject();
            List<ModelDownloader.FileSpec> files = new ArrayList<>();
            long size = 0;
            for (JsonElement element : json.getAsJsonArray("files")) {
                JsonObject file = element.getAsJsonObject();
                ModelDownloader.FileSpec spec = new ModelDownloader.FileSpec(file.get("path").getAsString(),
                        file.get("url").getAsString(), file.get("sha256").getAsString(), file.get("size").getAsLong());
                files.add(spec);
                size += spec.size();
            }
            components.put(entry.getKey(), new Component(entry.getKey(), json.get("type").getAsString(), size,
                    json.get("license").getAsString(), json.get("attribution").getAsString(), List.copyOf(files), json));
        }

        List<Language> languages = new ArrayList<>();
        JsonArray languageArray = root.getAsJsonArray("languages");
        for (JsonElement element : languageArray) {
            JsonObject json = element.getAsJsonObject();
            languages.add(new Language(json.get("code").getAsString(), json.get("name").getAsString(),
                    json.get("englishName").getAsString(), optional(json, "speech"), optional(json, "toEnglish"),
                    optional(json, "fromEnglish")));
        }
        return new Catalog(Set.copyOf(hosts), Map.copyOf(components), List.copyOf(languages));
    }

    private static String optional(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    public Set<String> hosts() {
        return hosts;
    }

    public List<Language> languages() {
        return languages;
    }

    public Optional<Language> language(String code) {
        return languages.stream().filter(language -> language.code().equals(code)).findFirst();
    }

    public Component component(String id) {
        Component component = components.get(id);
        if (component == null) {
            throw new IllegalArgumentException("Unknown model component " + id);
        }
        return component;
    }

    /** Total download size of a language's components, counting components shared between languages once. */
    public long size(Language language) {
        return language.components().stream().distinct().mapToLong(id -> component(id).size()).sum();
    }
}
