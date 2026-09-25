package io.github.eiriksb.sipher.asr;

import java.util.Locale;
import java.util.regex.Pattern;

/** Drops recogniser output that is not worth showing: fillers, noise tags and punctuation-only results. */
public final class TranscriptFilter {
    private static final Pattern FILLER = Pattern.compile("^(?:(?:uh+|um+|mm+|hmm+|ah+|oh+|huh|mhm|er+|eh)[\\s,.!?…-]*)+$");
    private static final Pattern NOISE_TAG = Pattern.compile("^[\\[(<*][^\\])>*]{1,40}[\\])>*]$");
    private static final Pattern HAS_WORD = Pattern.compile("[\\p{L}\\p{N}]");

    private TranscriptFilter() {
    }

    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty() || !HAS_WORD.matcher(normalized).find()) {
            return "";
        }
        if (NOISE_TAG.matcher(normalized).matches() || FILLER.matcher(normalized.toLowerCase(Locale.ROOT)).matches()) {
            return "";
        }
        return normalized;
    }
}
