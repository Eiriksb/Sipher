package io.github.eiriksb.sipher.neoforge;

//? if neoforge {
import io.github.eiriksb.sipher.Sipher;
import io.github.eiriksb.sipher.config.SipherServerConfig;
import io.github.eiriksb.sipher.net.CaptionPayload;
import io.github.eiriksb.sipher.net.CaptionUpdatePayload;
import io.github.eiriksb.sipher.net.SipherNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** NeoForge entry point. */
@Mod(Sipher.MOD_ID)
public final class SipherNeoForge {
    public SipherNeoForge(IEventBus modBus, ModContainer container) {
        Sipher.init(new NeoForgePlatform());
        modBus.addListener(SipherNeoForge::registerPayloads);
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> SipherServerConfig.load(
                event.getServer().getWorldPath(LevelResource.ROOT), Sipher.platform().gameDirectory()));

        //? if >=1.21.9 {
        /*if (FMLEnvironment.getDist().isClient()) {
        *///?} else
        if (FMLEnvironment.dist.isClient()) {
            SipherNeoForgeClient.init(modBus, container);
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(SipherNetwork.PROTOCOL).optional();
        registrar.playToServer(CaptionUpdatePayload.TYPE, CaptionUpdatePayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                SipherNetwork.receivedOnServer(player, payload);
            }
        });
        registrar.playToClient(CaptionPayload.TYPE, CaptionPayload.STREAM_CODEC,
                (payload, context) -> SipherNetwork.receivedOnClient(payload));
    }
}
//?}
