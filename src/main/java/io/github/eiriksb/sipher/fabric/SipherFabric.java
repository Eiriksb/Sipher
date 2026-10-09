package io.github.eiriksb.sipher.fabric;

//? if fabric {
/*import io.github.eiriksb.sipher.Sipher;
import io.github.eiriksb.sipher.config.SipherServerConfig;
import io.github.eiriksb.sipher.net.CaptionPayload;
import io.github.eiriksb.sipher.net.CaptionUpdatePayload;
import io.github.eiriksb.sipher.net.SipherNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.level.storage.LevelResource;

// Fabric entry point.
public final class SipherFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Sipher.init(new FabricPlatform());
        // Fabric only sends a payload to a player whose client registered it, so both stay optional.
        PayloadTypeRegistry.playC2S().register(CaptionUpdatePayload.TYPE, CaptionUpdatePayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(CaptionPayload.TYPE, CaptionPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CaptionUpdatePayload.TYPE,
                (payload, context) -> SipherNetwork.receivedOnServer(context.player(), payload));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> SipherServerConfig.load(
                server.getWorldPath(LevelResource.ROOT), Sipher.platform().gameDirectory()));
    }
}
*///?}
