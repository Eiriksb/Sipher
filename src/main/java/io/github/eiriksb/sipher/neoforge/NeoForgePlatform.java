package io.github.eiriksb.sipher.neoforge;

//? if neoforge {
import io.github.eiriksb.sipher.api.PlayerCaptionEvent;
import io.github.eiriksb.sipher.net.CaptionPayload;
import io.github.eiriksb.sipher.platform.Platform;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.Optional;

final class NeoForgePlatform implements Platform {
    @Override
    public String loader() {
        return "NeoForge " + modVersion("neoforge").orElse("?");
    }

    @Override
    public Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Optional<String> modVersion(String modId) {
        return ModList.get().getModContainerById(modId).map(mod -> mod.getModInfo().getVersion().toString());
    }

    @Override
    public boolean hasSipher(ServerPlayer player) {
        return player.connection.hasChannel(CaptionPayload.TYPE);
    }

    @Override
    public void send(ServerPlayer player, CaptionPayload caption) {
        PacketDistributor.sendToPlayer(player, caption);
    }

    @Override
    public void post(PlayerCaptionEvent event) {
        NeoForge.EVENT_BUS.post(event);
    }
}
//?}
