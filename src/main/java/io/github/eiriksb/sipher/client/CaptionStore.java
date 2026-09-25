package io.github.eiriksb.sipher.client;

import io.github.eiriksb.sipher.config.SipherClientConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Caption lines shown above each player's head. Main (render) thread only. */
public final class CaptionStore {
    private static final int MAX_LINES_PER_PLAYER = 3;
    /** A live caption disappears if its speaker goes quiet without a final caption arriving. */
    private static final long PARTIAL_TIMEOUT_MS = 4000;

    private static final Map<UUID, TreeMap<Integer, Line>> LINES = new HashMap<>();

    private CaptionStore() {
    }

    public record View(String text, float alpha, boolean partial) {
    }

    private static final class Line {
        String text;
        boolean partial;
        long visibleUntil;
    }

    public static void put(UUID player, int line, String text, boolean partial) {
        TreeMap<Integer, Line> lines = LINES.computeIfAbsent(player, id -> new TreeMap<>());
        Line entry = lines.computeIfAbsent(line, id -> new Line());
        entry.text = text;
        entry.partial = partial;
        entry.visibleUntil = System.currentTimeMillis() + (partial ? PARTIAL_TIMEOUT_MS : SipherClientConfig.BUBBLE_DISPLAY_MS.get());
        while (lines.size() > MAX_LINES_PER_PLAYER) {
            lines.pollFirstEntry();
        }
    }

    public static void remove(UUID player, int line) {
        TreeMap<Integer, Line> lines = LINES.get(player);
        if (lines != null) {
            lines.remove(line);
            if (lines.isEmpty()) {
                LINES.remove(player);
            }
        }
    }

    public static List<View> lines(UUID player) {
        TreeMap<Integer, Line> lines = LINES.get(player);
        if (lines == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        long fade = SipherClientConfig.BUBBLE_FADE_MS.get();
        List<View> views = new ArrayList<>(lines.size());
        for (Line line : lines.values()) {
            long overdue = now - line.visibleUntil;
            if (overdue <= 0) {
                views.add(new View(line.text, 1f, line.partial));
            } else if (overdue < fade) {
                views.add(new View(line.text, 1f - overdue / (float) fade, line.partial));
            }
        }
        return views;
    }

    /** Drops lines that have fully faded out. */
    public static void prune() {
        long now = System.currentTimeMillis();
        long fade = SipherClientConfig.BUBBLE_FADE_MS.get();
        LINES.values().forEach(lines -> lines.values().removeIf(line -> now - line.visibleUntil >= fade));
        LINES.values().removeIf(Map::isEmpty);
    }

    public static void clear() {
        LINES.clear();
    }
}
