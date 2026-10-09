package io.github.eiriksb.sipher;

import io.github.eiriksb.sipher.platform.Platform;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/** Shared by every loader. The loader's entry point calls {@link #init(Platform)} first. */
public final class Sipher {
    public static final String MOD_ID = "sipher";
    public static final Logger LOGGER = LoggerFactory.getLogger("Sipher");

    private static Platform platform;

    private Sipher() {
    }

    public static void init(Platform loader) {
        platform = loader;
    }

    public static Platform platform() {
        return platform;
    }

    /** {@code <game>/sipher}: extracted natives, built-in models and downloaded language packs. */
    public static Path directory() {
        return platform.gameDirectory().resolve(MOD_ID);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
