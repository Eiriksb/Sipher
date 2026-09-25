package io.github.eiriksb.sipher.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Recent captions for the transcript box, oldest first. Main (render) thread only. */
public final class CaptionLog {
    private static final int CAPACITY = 50;
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static long lastUpdate;

    private CaptionLog() {
    }

    public record Entry(UUID speaker, String name, int line, String text, boolean partial) {
    }

    public static void put(UUID speaker, String name, int line, String text, boolean partial) {
        lastUpdate = System.currentTimeMillis();
        Entry updated = new Entry(speaker, name, line, text, partial);
        // Replace the live line in place so a partial caption grows instead of stacking up.
        for (int i = ENTRIES.size() - 1; i >= 0; i--) {
            Entry entry = ENTRIES.get(i);
            if (entry.speaker().equals(speaker) && entry.line() == line) {
                ENTRIES.set(i, updated);
                return;
            }
        }
        ENTRIES.add(updated);
        if (ENTRIES.size() > CAPACITY) {
            ENTRIES.removeFirst();
        }
    }

    public static void remove(UUID speaker, int line) {
        ENTRIES.removeIf(entry -> entry.speaker().equals(speaker) && entry.line() == line);
    }

    public static List<Entry> recent(int count) {
        return List.copyOf(ENTRIES.subList(Math.max(0, ENTRIES.size() - count), ENTRIES.size()));
    }

    /** Milliseconds since the last caption arrived. */
    public static long idleMillis() {
        return lastUpdate == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastUpdate;
    }

    public static void clear() {
        ENTRIES.clear();
        lastUpdate = 0;
    }
}
