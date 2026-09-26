package io.github.eiriksb.sipher.client;

import io.github.eiriksb.sipher.Sipher;
import io.github.eiriksb.sipher.config.SipherClientConfig;
import io.github.eiriksb.sipher.models.LanguagePacks;
import io.github.eiriksb.sipher.runtime.NativePlatform;
import io.github.eiriksb.sipher.runtime.NativeRuntime;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A plain-text report for bug reports: versions, platform, native and speech status, languages and recent Sipher
 * warnings. Sipher has no telemetry, so this is copied to the clipboard only when the player asks for it. It contains
 * no captions, no player names and no server address.
 */
public final class Diagnostics {
    private static final int RECENT_PROBLEMS = 20;
    private static final Deque<String> PROBLEMS = new ArrayDeque<>();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Diagnostics() {
    }

    /** Starts remembering Sipher's recent warnings and errors (Minecraft logs through Log4j). */
    public static void install() {
        try {
            AbstractAppender appender = new AbstractAppender("SipherDiagnostics", null, null, true, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                        remember(event);
                    }
                }
            };
            appender.start();
            ((Logger) LogManager.getLogger(Sipher.LOGGER.getName())).addAppender(appender);
        } catch (RuntimeException | LinkageError e) {
            Sipher.LOGGER.debug("Diagnostics cannot collect recent warnings", e);
        }
    }

    private static void remember(LogEvent event) {
        String line = TIME.format(Instant.ofEpochMilli(event.getTimeMillis())) + " " + event.getLevel() + " "
                + event.getMessage().getFormattedMessage();
        Throwable thrown = event.getThrown();
        if (thrown != null) {
            line += " (" + thrown + ")";
        }
        synchronized (PROBLEMS) {
            PROBLEMS.addLast(line);
            while (PROBLEMS.size() > RECENT_PROBLEMS) {
                PROBLEMS.removeFirst();
            }
        }
    }

    public static String report() {
        List<String> lines = new ArrayList<>();
        lines.add("Sipher " + version(Sipher.MOD_ID) + ", Minecraft " + version("minecraft") + ", NeoForge "
                + version("neoforge") + ", Simple Voice Chat " + version("voicechat"));
        Runtime runtime = Runtime.getRuntime();
        lines.add("Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + "), max heap "
                + runtime.maxMemory() / 1_048_576 + " MB");
        lines.add("OS " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " ("
                + System.getProperty("os.arch") + "), " + runtime.availableProcessors() + " CPU threads" + systemMemory());

        NativeRuntime.Status natives = NativeRuntime.status();
        lines.add("Natives: " + natives.state() + " on " + NativePlatform.current().map(NativePlatform::id).orElse("unsupported platform")
                + (natives.translationSupported() ? ", translation supported" : "") + " (" + natives.message() + ")");
        lines.add("Speech: " + CaptionEngine.state() + " (" + CaptionEngine.message() + "), microphone "
                + (CaptionEngine.hearingMicrophone() ? "heard in the last second" : "silent"));
        lines.add("Languages: speak " + SipherClientConfig.SPOKEN_LANGUAGE.get() + ", read " + SipherClientConfig.READING_LANGUAGE.get()
                + ", Minecraft " + Minecraft.getInstance().options.languageCode);
        lines.add("Language packs: " + packs());
        lines.add("Settings: captions " + onOff(SipherClientConfig.CAPTIONS_ENABLED.get())
                + ", share " + onOff(SipherClientConfig.SHARE_MY_CAPTIONS.get())
                + ", live " + onOff(SipherClientConfig.LIVE_PARTIALS.get())
                + ", welcome seen " + onOff(SipherClientConfig.WELCOME_SEEN.get())
                + ", downloads " + onOff(SipherClientConfig.ALLOW_DOWNLOADS.get())
                + ", recognizer threads " + SipherClientConfig.RECOGNIZER_THREADS.get());
        Minecraft minecraft = Minecraft.getInstance();
        String world = minecraft.getConnection() == null ? "not in a world"
                : (minecraft.isLocalServer() ? "singleplayer" : "multiplayer") + ", server "
                + (CaptionEngine.serverRelays() ? "has Sipher" : "has no Sipher");
        lines.add("World: " + world);

        lines.add("Recent Sipher warnings and errors:");
        synchronized (PROBLEMS) {
            if (PROBLEMS.isEmpty()) {
                lines.add("  none");
            }
            PROBLEMS.forEach(problem -> lines.add("  " + problem));
        }
        // Public bug reports shouldn't carry the player's account name through file paths.
        String home = System.getProperty("user.home");
        String report = String.join("\n", lines);
        return home == null || home.length() < 2 ? report : report.replace(home, "~");
    }

    private static String packs() {
        LanguagePacks packs = CaptionEngine.packs();
        if (packs == null) {
            return "not loaded";
        }
        String present = packs.catalog().languages().stream()
                .map(language -> Map.entry(language.code(), packs.status(language)))
                .filter(entry -> entry.getValue().state() != LanguagePacks.State.AVAILABLE)
                .map(entry -> entry.getKey() + " " + entry.getValue().state().name().toLowerCase(Locale.ROOT)
                        + (entry.getValue().error() == null ? "" : " (" + entry.getValue().error() + ")"))
                .collect(Collectors.joining(", "));
        return (present.isEmpty() ? "none installed" : present) + "; catalogue has " + packs.catalog().languages().size() + " languages";
    }

    private static String systemMemory() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return ", " + os.getTotalMemorySize() / 1_048_576 + " MB RAM";
        }
        return "";
    }

    private static String version(String modId) {
        return ModList.get().getModContainerById(modId).map(mod -> mod.getModInfo().getVersion().toString()).orElse("missing");
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }
}
