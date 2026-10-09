package io.github.eiriksb.sipher.fabric;

//? if fabric {
/*import io.github.eiriksb.sipher.client.SipherClient;
import io.github.eiriksb.sipher.client.TranscriptOverlay;
import io.github.eiriksb.sipher.net.CaptionPayload;
import io.github.eiriksb.sipher.net.CaptionUpdatePayload;
import io.github.eiriksb.sipher.net.SipherNetwork;
import io.github.eiriksb.sipher.platform.Platform;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.ChatScreen;
//? if >=1.21.6 {
import io.github.eiriksb.sipher.Sipher;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//?} else
/^import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;^/

/^* Fabric client events, forwarded to {@link SipherClient}. ^/
public final class SipherFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SipherClient.init(new Platform.Client() {
            @Override
            public boolean serverHasSipher() {
                return ClientPlayNetworking.canSend(CaptionUpdatePayload.TYPE);
            }

            @Override
            public void sendToServer(CaptionUpdatePayload update) {
                ClientPlayNetworking.send(update);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(CaptionPayload.TYPE, (payload, context) -> SipherNetwork.receivedOnClient(payload));
        KeyBindingHelper.registerKeyBinding(SipherClient.OPEN_SETTINGS);
        //? if >=1.21.6 {
        HudElementRegistry.addLast(Sipher.id("transcript"), TranscriptOverlay::render);
        //?} else
        /^HudRenderCallback.EVENT.register(TranscriptOverlay::render);^/

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> SipherClient.started());
        ClientTickEvents.END_CLIENT_TICK.register(client -> SipherClient.tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SipherClient.loggedOut());
        // Dragging the transcript box while chat is open
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof ChatScreen) {
                //? if >=1.21.9 {
                ScreenMouseEvents.allowMouseClick(screen).register(
                        (chat, click) -> !TranscriptOverlay.mousePressed(click.x(), click.y(), click.button()));
                ScreenMouseEvents.allowMouseRelease(screen).register((chat, click) -> !TranscriptOverlay.mouseReleased());
                //?} else {
                /^ScreenMouseEvents.allowMouseClick(screen).register(
                        (chat, mouseX, mouseY, button) -> !TranscriptOverlay.mousePressed(mouseX, mouseY, button));
                ScreenMouseEvents.allowMouseRelease(screen).register(
                        (chat, mouseX, mouseY, button) -> !TranscriptOverlay.mouseReleased());
                ^///?}
                ScreenEvents.afterRender(screen).register(
                        (chat, graphics, mouseX, mouseY, partialTick) -> TranscriptOverlay.mouseDragged(mouseX, mouseY));
            }
        });
    }
}
*///?}
