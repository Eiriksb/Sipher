package io.github.eiriksb.sipher.mt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps Minecraft names intact through translation. General-purpose translation models mangle them ("creeper" became
 * "encruvio" in Spanish and "Geweihe" in German), while short placeholder codes pass through every model unchanged, so
 * names are swapped for placeholders before translating and restored afterwards. Players use the English names in most
 * languages, and most official Minecraft translations keep them too.
 */
public final class GameTerms {
    /** Canonical spelling of each protected name, singular and plural. Longest first so phrases win. */
    private static final String[][] TERMS = {
            {"Ender Dragon", "Ender Dragons"}, {"Ender Pearl", "Ender Pearls"}, {"Endermite", "Endermites"},
            {"Enderman", "Endermen"}, {"Creeper", "Creepers"}, {"Netherite", "Netherite"}, {"Nether", "Nether"},
            {"Redstone", "Redstone"}, {"Elytra", "Elytras"}, {"Ghast", "Ghasts"}, {"Piglin", "Piglins"},
            {"Shulker", "Shulkers"}, {"Hoglin", "Hoglins"}, {"Wither", "Withers"}, {"Minecraft", "Minecraft"},
    };
    private static final Pattern PATTERN;

    static {
        List<String> alternatives = new ArrayList<>();
        for (String[] term : TERMS) {
            alternatives.add(Pattern.quote(term[1].toLowerCase(Locale.ROOT)));
            alternatives.add(Pattern.quote(term[0].toLowerCase(Locale.ROOT)));
        }
        PATTERN = Pattern.compile("(?iu)\\b(" + String.join("|", alternatives) + ")\\b");
    }

    private GameTerms() {
    }

    /** Text with game names replaced by placeholders, plus what each placeholder stands for. */
    public record Masked(String text, String prefix, List<String> names) {
        public String restore(String translated) {
            String result = translated;
            for (int i = names.size() - 1; i >= 0; i--) {
                result = result.replace(prefix + (i + 1), names.get(i));
            }
            return result;
        }
    }

    public static Masked protect(String text) {
        String prefix = "X";
        for (String candidate : new String[]{"X", "Q", "Z", "K"}) {
            if (!text.matches("(?s).*\\b" + candidate + "\\d.*")) {
                prefix = candidate;
                break;
            }
        }
        List<String> names = new ArrayList<>();
        Matcher matcher = PATTERN.matcher(text);
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            String canonical = canonical(matcher.group(1));
            int index = names.indexOf(canonical);
            if (index < 0) {
                names.add(canonical);
                index = names.size() - 1;
            }
            matcher.appendReplacement(masked, Matcher.quoteReplacement(prefix + (index + 1)));
        }
        matcher.appendTail(masked);
        return new Masked(masked.toString(), prefix, List.copyOf(names));
    }

    private static String canonical(String match) {
        String lower = match.toLowerCase(Locale.ROOT);
        for (String[] term : TERMS) {
            if (term[0].toLowerCase(Locale.ROOT).equals(lower)) {
                return term[0];
            }
            if (term[1].toLowerCase(Locale.ROOT).equals(lower)) {
                return term[1];
            }
        }
        return match;
    }
}
