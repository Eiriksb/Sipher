package io.github.eiriksb.sipher.runtime;

import java.util.Locale;
import java.util.Optional;

/** The native platforms Sipher ships libraries for. Ids match sherpa-onnx's native-lib jar names. */
public enum NativePlatform {
    LINUX_X64("linux-x64", "lib", ".so"),
    LINUX_AARCH64("linux-aarch64", "lib", ".so"),
    OSX_X64("osx-x64", "lib", ".dylib"),
    OSX_AARCH64("osx-aarch64", "lib", ".dylib"),
    WIN_X64("win-x64", "", ".dll"),
    WIN_ARM64("win-arm64", "", ".dll");

    private final String id;
    private final String prefix;
    private final String suffix;

    NativePlatform(String id, String prefix, String suffix) {
        this.id = id;
        this.prefix = prefix;
        this.suffix = suffix;
    }

    public String id() {
        return id;
    }

    /** Platform file name of a library, e.g. {@code onnxruntime} → {@code libonnxruntime.so}. */
    public String libraryFile(String name) {
        return prefix + name + suffix;
    }

    public static Optional<NativePlatform> current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        if (!arm64 && !x64) {
            return Optional.empty();
        }
        if (os.contains("win")) {
            return Optional.of(arm64 ? WIN_ARM64 : WIN_X64);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Optional.of(arm64 ? OSX_AARCH64 : OSX_X64);
        }
        if (os.contains("linux")) {
            return Optional.of(arm64 ? LINUX_AARCH64 : LINUX_X64);
        }
        return Optional.empty();
    }
}
