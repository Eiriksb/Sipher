package io.github.eiriksb.sipher;

import io.github.eiriksb.sipher.config.SipherClientConfig;
import io.github.eiriksb.sipher.config.SipherServerConfig;
import io.github.eiriksb.sipher.net.SipherNetwork;
import io.github.eiriksb.sipher.runtime.NativeRuntime;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

@Mod(Sipher.MOD_ID)
public final class Sipher {
    public static final String MOD_ID = "sipher";
    public static final Logger LOGGER = LoggerFactory.getLogger("Sipher");

    public Sipher(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SipherClientConfig.SPEC);
        container.registerConfig(ModConfig.Type.SERVER, SipherServerConfig.SPEC);
        modBus.addListener(SipherNetwork::register);

        if (FMLEnvironment.dist.isClient()) {
            // Point the native loaders at Sipher's directory before anything else can initialise them. Extraction and
            // loading happen later on a background thread.
            NativeRuntime.configure(directory());
        }
    }

    /** {@code <game>/sipher}: extracted natives, built-in models and downloaded language packs. */
    public static Path directory() {
        return FMLPaths.GAMEDIR.get().resolve(MOD_ID);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
