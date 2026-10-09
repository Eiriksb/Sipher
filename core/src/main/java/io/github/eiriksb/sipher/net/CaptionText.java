package io.github.eiriksb.sipher.net;

import java.util.Locale;
import java.util.regex.Pattern;

/** Validation shared by both caption payloads. Captions are untrusted input from other clients. */
public final class CaptionText {
    /** Hard cap on the wire; the server config can lower it further. */
    public static final int MAX_WIRE_LENGTH = 512;

    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(-[a-z0-9]{2,8})?");
    // Formatting codes (§x), control characters and bidi overrides have no place in a caption.
    private static final Pattern UNSAFE = Pattern.compile("§.|[\\p{Cc}\\p{Cf}]");

    private CaptionText() {
    }

    public static String sanitize(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String clean = UNSAFE.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength).trim();
    }

    public static String language(String code) {
        String normalized = code == null ? "" : code.toLowerCase(Locale.ROOT).trim();
        return LANGUAGE.matcher(normalized).matches() ? normalized : "und";
    }
}
