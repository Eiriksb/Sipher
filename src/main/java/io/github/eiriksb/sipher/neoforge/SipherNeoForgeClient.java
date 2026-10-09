package io.github.eiriksb.sipher.neoforge;

//? if neoforge {
import io.github.eiriksb.sipher.Sipher;
import io.github.eiriksb.sipher.client.SipherClient;
import io.github.eiriksb.sipher.client.SipherSettingsScreen;
import io.github.eiriksb.sipher.client.TranscriptOverlay;
import io.github.eiriksb.sipher.net.CaptionPayload;
import io.github.eiriksb.sipher.net.CaptionUpdatePayload;
import io.github.eiriksb.sipher.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.payload.MinecraftRegisterPayload;
//? if >=1.21.6 {
/*import net.neoforged.neoforge.client.network.ClientPacketDistributor;
*///?} else
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Set;

/** NeoForge client events, forwarded to {@link SipherClient}. */
final class SipherNeoForgeClient {
    private SipherNeoForgeClient() {
    }

    static void init(IEventBus modBus, ModContainer container) {
        SipherClient.init(new Platform.Client() {
            @Override
            public boolean serverHasSipher() {
                ClientPacketListener connection = Minecraft.getInstance().getConnection();
                return connection != null && connection.hasChannel(CaptionUpdatePayload.TYPE);
            }

            @Override
            public void sendToServer(CaptionUpdatePayload update) {
                //? if >=1.21.6 {
                /*ClientPacketDistributor.sendToServer(update);
                *///?} else
                PacketDistributor.sendToServer(update);
            }
        });
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new SipherSettingsScreen(parent));

        modBus.addListener((FMLClientSetupEvent event) -> SipherClient.started());
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            //? if >=1.21.9
            //event.registerCategory(SipherClient.KEY_CATEGORY);
            event.register(SipherClient.OPEN_SETTINGS);
        });
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(Sipher.id("transcript"), TranscriptOverlay::render));

        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> SipherClient.tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> SipherClient.loggedOut());
        // On a server that isn't NeoForge (Fabric), NeoForge announces the channels it can receive only while
        // configuring, which Fabric doesn't carry over into the game. Announce the caption channel again once in game,
        // or Fabric servers never send other players' captions to this player.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            if (connection != null && !connection.getConnectionType().isNeoForge()) {
                connection.send(new MinecraftRegisterPayload(Set.of(CaptionPayload.TYPE.id())));
            }
        });
        // Dragging the transcript box while chat is open
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonPressed.Pre event) -> {
            if (TranscriptOverlay.mousePressed(event.getMouseX(), event.getMouseY(), event.getButton())) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseDragged.Pre event) -> {
            if (TranscriptOverlay.mouseDragged(event.getMouseX(), event.getMouseY())) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonReleased.Pre event) -> {
            if (TranscriptOverlay.mouseReleased()) {
                event.setCanceled(true);
            }
        });
    }
}
//?}
