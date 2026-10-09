package io.github.eiriksb.sipher.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A settings file in TOML: typed values in {@code [sections]}, each with a comment. Sipher reads and writes it itself, so
 * settings behave the same on every mod loader, and it reads the files NeoForge's config system wrote for earlier
 * versions. Missing or mistyped values fall back to their default and out-of-range numbers are clamped; the file is then
 * rewritten with the corrected values. Only the parts of TOML these files use are supported.
 */
public final class ConfigSpec {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sipher");

    private final List<ConfigValue<?>> values;
    private Path file;
    private FileTime loadedModified;

    private ConfigSpec(List<ConfigValue<?>> values) {
        this.values = List.copyOf(values);
    }

    /** Reads {@code file} (writing it with defaults if it doesn't exist yet); later {@link #save()}s go there. */
    public synchronized void load(Path file) {
        this.file = file;
        Map<String, Object> read = Map.of();
        if (Files.exists(file)) {
            try {
                read = parse(Files.readAllLines(file, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not read {}; using default settings", file, e);
            }
        }
        boolean corrected = false;
        for (ConfigValue<?> value : values) {
            corrected |= !value.load(read.get(value.path()));
        }
        if (corrected || !Files.exists(file)) {
            save();
        } else {
            loadedModified = modified(file);
        }
    }

    /** Re-reads the file if it changed on disk since it was loaded or saved, for example edited by a server admin. */
    public synchronized void reloadIfChanged() {
        if (file != null && loadedModified != null && !loadedModified.equals(modified(file))) {
            LOGGER.info("Reloading {}", file);
            load(file);
        }
    }

    /** Writes the current values. Does nothing before {@link #load(Path)}. */
    public synchronized void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, format(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            loadedModified = modified(file);
        } catch (IOException e) {
            LOGGER.warn("Could not save {}", file, e);
        }
    }

    private static FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return null;
        }
    }

    String format() {
        StringBuilder out = new StringBuilder();
        String section = null;
        for (ConfigValue<?> value : values) {
            if (!value.section.equals(section)) {
                if (section != null) {
                    out.append('\n');
                }
                section = value.section;
                out.append('[').append(section).append("]\n");
            }
            for (String line : value.comment) {
                out.append("\t#").append(line).append('\n');
            }
            out.append("\t# Default: ").append(value.literal(true)).append('\n');
            String range = value.range();
            if (range != null) {
                out.append("\t# Range: ").append(range).append('\n');
            }
            out.append('\t').append(value.key).append(" = ").append(value.literal(false)).append('\n');
        }
        return out.toString();
    }

    /** Parses {@code key = value} lines under {@code [section]} headers into "section.key" → Boolean, Long, Double or String. */
    static Map<String, Object> parse(List<String> lines) {
        Map<String, Object> result = new HashMap<>();
        String section = "";
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                section = end > 1 ? line.substring(1, end).strip() : "";
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = unquoteKey(line.substring(0, equals).strip());
            Object value = parseValue(line.substring(equals + 1).strip());
            if (value != null) {
                result.put(section.isEmpty() ? key : section + "." + key, value);
            }
        }
        return result;
    }

    private static String unquoteKey(String key) {
        if (key.length() >= 2 && (key.startsWith("\"") && key.endsWith("\"") || key.startsWith("'") && key.endsWith("'"))) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    private static Object parseValue(String text) {
        if (text.startsWith("\"")) {
            return basicString(text);
        }
        if (text.startsWith("'")) {
            int end = text.indexOf('\'', 1);
            return end < 0 ? null : text.substring(1, end);
        }
        int comment = text.indexOf('#');
        String value = (comment < 0 ? text : text.substring(0, comment)).strip();
        if (value.equals("true") || value.equals("false")) {
            return Boolean.parseBoolean(value);
        }
        String number = value.replace("_", "");
        try {
            return Long.parseLong(number.startsWith("+") ? number.substring(1) : number);
        } catch (NumberFormatException e) {
            // not an integer
        }
        try {
            double parsed = Double.parseDouble(number);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String basicString(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char escape = text.charAt(++i);
            switch (escape) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u', 'U' -> {
                    int digits = escape == 'u' ? 4 : 8;
                    if (i + digits >= text.length()) {
                        return null;
                    }
                    out.appendCodePoint(Integer.parseInt(text.substring(i + 1, i + 1 + digits), 16));
                    i += digits;
                }
                default -> out.append(escape);
            }
        }
        return null; // unterminated
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        out.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Defines values in the order they are written to the file. */
    public static final class Builder {
        private final Map<String, ConfigValue<?>> values = new LinkedHashMap<>();
        private final Deque<String> sections = new ArrayDeque<>();
        private String[] comment = new String[0];

        public Builder push(String section) {
            sections.addLast(section);
            return this;
        }

        public Builder pop() {
            sections.removeLast();
            return this;
        }

        /** Comment for the next value defined. */
        public Builder comment(String... lines) {
            comment = lines;
            return this;
        }

        public BooleanValue define(String key, boolean defaultValue) {
            return add(new BooleanValue(section(), key, take(), defaultValue));
        }

        public ConfigValue<String> define(String key, String defaultValue) {
            return add(new StringValue(section(), key, take(), defaultValue));
        }

        public IntValue defineInRange(String key, int defaultValue, int min, int max) {
            return add(new IntValue(section(), key, take(), defaultValue, min, max));
        }

        public DoubleValue defineInRange(String key, double defaultValue, double min, double max) {
            return add(new DoubleValue(section(), key, take(), defaultValue, min, max));
        }

        public ConfigSpec build() {
            if (!sections.isEmpty()) {
                throw new IllegalStateException("Unclosed config section " + sections.peekLast());
            }
            return new ConfigSpec(new ArrayList<>(values.values()));
        }

        private String section() {
            if (sections.isEmpty()) {
                throw new IllegalStateException("Config values must be inside a section");
            }
            return String.join(".", sections);
        }

        private String[] take() {
            String[] taken = comment;
            comment = new String[0];
            return taken;
        }

        private <V extends ConfigValue<?>> V add(V value) {
            if (values.putIfAbsent(value.path(), value) != null) {
                throw new IllegalStateException("Duplicate config value " + value.path());
            }
            return value;
        }
    }

    /** One setting. Thread-safe: speech and network threads read settings while the game thread changes them. */
    public abstract static class ConfigValue<T> implements Supplier<T> {
        final String section;
        final String key;
        final String[] comment;
        final T defaultValue;
        private volatile T value;

        ConfigValue(String section, String key, String[] comment, T defaultValue) {
            this.section = section;
            this.key = key;
            this.comment = comment;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        @Override
        public T get() {
            return value;
        }

        /** Changes the value in memory; call {@link ConfigSpec#save()} to write it. */
        public void set(T value) {
            T corrected = value == null ? null : correct(value);
            this.value = corrected == null ? defaultValue : corrected;
        }

        public T getDefault() {
            return defaultValue;
        }

        String path() {
            return section + "." + key;
        }

        /** Takes a value read from the file; false if it was missing or had to be corrected. */
        boolean load(Object read) {
            T corrected = read == null ? null : correct(read);
            value = corrected == null ? defaultValue : corrected;
            return corrected != null && unchanged(corrected, read);
        }

        /** Whether the file already holds {@code corrected} exactly as it would be written. */
        boolean unchanged(T corrected, Object read) {
            return corrected.equals(read);
        }

        /** The value made valid, or null when it can't be used at all. */
        abstract T correct(Object raw);

        abstract String literal(T value);

        /** The default or current value as written in the file. */
        String literal(boolean defaults) {
            return literal(defaults ? defaultValue : value);
        }

        String range() {
            return null;
        }
    }

    public static final class BooleanValue extends ConfigValue<Boolean> {
        BooleanValue(String section, String key, String[] comment, boolean defaultValue) {
            super(section, key, comment, defaultValue);
        }

        @Override
        Boolean correct(Object raw) {
            return raw instanceof Boolean bool ? bool : null;
        }

        @Override
        String literal(Boolean value) {
            return value.toString();
        }
    }

    public static final class IntValue extends ConfigValue<Integer> {
        private final int min;
        private final int max;

        IntValue(String section, String key, String[] comment, int defaultValue, int min, int max) {
            super(section, key, comment, defaultValue);
            this.min = min;
            this.max = max;
        }

        @Override
        boolean unchanged(Integer corrected, Object read) {
            // Integers are read as Long.
            return read instanceof Long number && number == corrected.longValue();
        }

        @Override
        Integer correct(Object raw) {
            if (raw instanceof Long || raw instanceof Integer) {
                return (int) Math.clamp(((Number) raw).longValue(), min, max);
            }
            if (raw instanceof Double number && number == Math.rint(number)) {
                return (int) Math.clamp(number.longValue(), min, max);
            }
            return null;
        }

        @Override
        String literal(Integer value) {
            return value.toString();
        }

        @Override
        String range() {
            return min + " ~ " + max;
        }
    }

    public static final class DoubleValue extends ConfigValue<Double> {
        private final double min;
        private final double max;

        DoubleValue(String section, String key, String[] comment, double defaultValue, double min, double max) {
            super(section, key, comment, defaultValue);
            this.min = min;
            this.max = max;
        }

        @Override
        boolean unchanged(Double corrected, Object read) {
            // A whole number may be written without a decimal point.
            return read instanceof Number number && number.doubleValue() == corrected;
        }

        @Override
        Double correct(Object raw) {
            return raw instanceof Number number && Double.isFinite(number.doubleValue())
                    ? Math.clamp(number.doubleValue(), min, max) : null;
        }

        @Override
        String literal(Double value) {
            return value.toString();
        }

        @Override
        String range() {
            return min + " ~ " + max;
        }
    }

    static final class StringValue extends ConfigValue<String> {
        StringValue(String section, String key, String[] comment, String defaultValue) {
            super(section, key, comment, defaultValue);
        }

        @Override
        String correct(Object raw) {
            return raw instanceof String text ? text : null;
        }

        @Override
        String literal(String value) {
            return quote(value);
        }
    }
}
