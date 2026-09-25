package io.github.eiriksb.sipher.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.eiriksb.sipher.Sipher;
import io.github.eiriksb.sipher.config.SipherClientConfig;
import io.github.eiriksb.sipher.net.SipherNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.util.List;

@Mod(value = Sipher.MOD_ID, dist = Dist.CLIENT)
public final class SipherClient {
    private static final KeyMapping OPEN_SETTINGS = new KeyMapping(
            "key.sipher.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "key.categories.sipher");

    public SipherClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new SipherSettingsScreen(parent));
        SipherNetwork.setClientHandler(CaptionEngine::remoteCaption);

        modBus.addListener((FMLClientSetupEvent event) -> CaptionEngine.start());
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(OPEN_SETTINGS));
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(Sipher.id("transcript"), TranscriptOverlay::render));

        NeoForge.EVENT_BUS.addListener(SipherClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(SipherClient::onRenderNameTag);
        NeoForge.EVENT_BUS.addListener(SipherClient::onLoggingOut);
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

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN_SETTINGS.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new SipherSettingsScreen(null));
            }
        }
        CaptionStore.prune();
    }

    private static void onRenderNameTag(RenderNameTagEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(event.getEntity() instanceof Player player) || minecraft.player == null || player.isInvisibleTo(minecraft.player)) {
            return;
        }
        boolean self = player == minecraft.player;
        if (self ? !SipherClientConfig.SHOW_OWN_BUBBLES.get() || minecraft.options.getCameraType().isFirstPerson()
                : !SipherClientConfig.SHOW_OTHER_BUBBLES.get()) {
            return;
        }
        double maxDistance = SipherClientConfig.BUBBLE_MAX_DISTANCE.get();
        if (minecraft.player.distanceToSqr(player) > maxDistance * maxDistance) {
            return;
        }
        List<CaptionStore.View> captions = CaptionStore.lines(player.getUUID());
        if (!captions.isEmpty()) {
            BubbleRenderer.render(event.getPoseStack(), event.getMultiBufferSource(), player, captions,
                    minecraft.font, event.getPackedLight(), event.getPartialTick());
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        CaptionStore.clear();
        CaptionLog.clear();
    }
}
