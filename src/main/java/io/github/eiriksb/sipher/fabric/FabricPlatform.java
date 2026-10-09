package io.github.eiriksb.sipher.fabric;

//? if fabric {
/*import io.github.eiriksb.sipher.api.PlayerCaptionEvent;
import io.github.eiriksb.sipher.net.CaptionPayload;
import io.github.eiriksb.sipher.platform.Platform;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.Optional;

final class FabricPlatform implements Platform {
    @Override
    public String loader() {
        return "Fabric Loader " + modVersion("fabricloader").orElse("?") + ", Fabric API " + modVersion("fabric-api").orElse("missing");
    }

    @Override
    public Path gameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Optional<String> modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).map(mod -> mod.getMetadata().getVersion().getFriendlyString());
    }

    @Override
    public boolean hasSipher(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, CaptionPayload.TYPE);
    }

    @Override
    public void send(ServerPlayer player, CaptionPayload caption) {
        ServerPlayNetworking.send(player, caption);
    }

    @Override
    public void post(PlayerCaptionEvent event) {
        PlayerCaptionEvent.EVENT.invoker().onPlayerCaption(event);
    }
}
*///?}
