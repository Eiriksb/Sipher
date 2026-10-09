package io.github.eiriksb.sipher.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.eiriksb.sipher.Sipher;
import io.github.eiriksb.sipher.config.SipherClientConfig;
import io.github.eiriksb.sipher.net.SipherNetwork;
import io.github.eiriksb.sipher.platform.Platform;
import io.github.eiriksb.sipher.runtime.NativeRuntime;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** The client half of Sipher, shared by every loader. The loader's client entry point forwards its events here. */
public final class SipherClient {
    // Key categories are objects since 1.21.9. Fabric registers them with vanilla, NeoForge in RegisterKeyMappingsEvent.
    //? if >=1.21.9 && fabric {
    /*public static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(Sipher.id("captions"));
    *///?} elif >=1.21.9 {
    /*public static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(Sipher.id("captions"));
    *///?}
    public static final KeyMapping OPEN_SETTINGS = new KeyMapping(
            "key.sipher.settings", InputConstants.Type.KEYSYM, InputConstants.KEY_O,
            //? if >=1.21.9 {
            /*KEY_CATEGORY);
            *///?} else
            "key.categories.sipher");

    /** Render thread only. Snapshots are reused or dropped by Minecraft, so they are weak keys. */
    private static final Map<Object, BubbleRenderer.Bubble> BUBBLES = new WeakHashMap<>();

    private static Platform.Client platform;
    /** The welcome screen is offered once per game session at most, even if something else closes it. */
    private static boolean welcomeOffered;

    /**
     * {@code -Dsipher.debug.caption=<text>}: every few seconds the player says the text (shared with the server like real
     * speech), alternating with the nearest other entity, to check captions without a microphone. Never set in releases.
     */
    private static final String DEBUG_CAPTION = System.getProperty("sipher.debug.caption");
    private static int debugTicks;

    private SipherClient() {
    }

    /** As the mod is constructed, after {@link Sipher#init}. */
    public static void init(Platform.Client loader) {
        platform = loader;
        // Point the native loaders at Sipher's directory before anything else can initialise them. Extraction and
        // loading happen later on a background thread.
        NativeRuntime.configure(Sipher.directory());
        SipherClientConfig.load(Sipher.platform().configDirectory());
        Diagnostics.install();
        SipherNetwork.setClientHandler(CaptionEngine::remoteCaption);
    }

    public static Platform.Client platform() {
        return platform;
    }

    /** Once the game is set up: loads the speech models in the background. */
    public static void started() {
        CaptionEngine.start();
    }

    /** End of every client tick. */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN_SETTINGS.consumeClick()) {
            if (minecraft.screen == null) {
                minecraft.setScreen(new SipherSettingsScreen(null));
            }
        }
        if (minecraft.screen instanceof TitleScreen title && shouldWelcome()) {
            minecraft.setScreen(new WelcomeScreen(title));
        }
        // Players who skip the title screen (quick play, direct connect) see it when the world is ready.
        if (minecraft.player != null && minecraft.screen == null && shouldWelcome()) {
            minecraft.setScreen(new WelcomeScreen(null));
        }
        CaptionStore.prune();
        if (DEBUG_CAPTION != null && minecraft.player != null && minecraft.level != null && ++debugTicks % 80 == 0) {
            int line = debugTicks / 80;
            Entity speaker = minecraft.player;
            double nearest = 16 * 16;
            for (Entity entity : line % 2 == 0 ? List.<Entity>of() : minecraft.level.entitiesForRendering()) {
                if (entity != minecraft.player && entity.distanceToSqr(minecraft.player) < nearest) {
                    speaker = entity;
                    nearest = entity.distanceToSqr(minecraft.player);
                }
            }
            CaptionEngine.debugCaption(speaker, line, DEBUG_CAPTION);
        }
    }

    public static void loggedOut() {
        CaptionStore.clear();
        CaptionLog.clear();
    }

    private static boolean shouldWelcome() {
        if (welcomeOffered || SipherClientConfig.WELCOME_SEEN.get()) {
            return false;
        }
        welcomeOffered = true;
        return true;
    }

    /** The key that opens Sipher's settings, as the player has bound it. */
    static Component settingsKey() {
        return OPEN_SETTINGS.getTranslatedKeyMessage();
    }

    /**
     * The caption bubble above an entity this frame, or null: above players, and above any other entity a server mod
     * sends captions for (SipherCaptions).
     */
    public static BubbleRenderer.Bubble bubble(Entity entity, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || entity.isInvisibleTo(minecraft.player)) {
            return null;
        }
        boolean self = entity == minecraft.player;
        if (self ? !SipherClientConfig.SHOW_OWN_BUBBLES.get() || minecraft.options.getCameraType().isFirstPerson()
                : !SipherClientConfig.SHOW_OTHER_BUBBLES.get()) {
            return null;
        }
        List<CaptionStore.View> captions = CaptionStore.lines(entity.getUUID());
        if (captions.isEmpty()) {
            return null;
        }
        double maxDistance = SipherClientConfig.BUBBLE_MAX_DISTANCE.get();
        if (minecraft.player.distanceToSqr(entity) > maxDistance * maxDistance) {
            return null;
        }
        return BubbleRenderer.prepare(entity, captions, minecraft.font, partialTick);
    }

    /**
     * Since 1.21.2 Minecraft renders entities from a snapshot of their state, taken first. The bubble is worked out
     * from the entity while the snapshot is taken and looked up again when it is drawn.
     */
    public static void prepareBubble(Object renderState, Entity entity, float partialTick) {
        BubbleRenderer.Bubble bubble = bubble(entity, partialTick);
        if (bubble == null) {
            BUBBLES.remove(renderState);
        } else {
            BUBBLES.put(renderState, bubble);
        }
    }

    public static BubbleRenderer.Bubble preparedBubble(Object renderState) {
        return BUBBLES.get(renderState);
    }
}
