package io.github.eiriksb.sipher.platform;

import io.github.eiriksb.sipher.api.PlayerCaptionEvent;
import io.github.eiriksb.sipher.net.CaptionPayload;
import io.github.eiriksb.sipher.net.CaptionUpdatePayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.Optional;

/** What Sipher needs from the mod loader (Fabric or NeoForge). */
public interface Platform {
    /** The loader and its version, for diagnostics: "NeoForge 21.1.256". */
    String loader();

    Path gameDirectory();

    Path configDirectory();

    /** The version of a loaded mod, or empty when it isn't loaded. */
    Optional<String> modVersion(String modId);

    /** Whether the player's client has Sipher's network channel (players without Sipher don't). */
    boolean hasSipher(ServerPlayer player);

    void send(ServerPlayer player, CaptionPayload caption);

    /** Hands an accepted caption to other server mods. */
    void post(PlayerCaptionEvent event);

    /** The client side, installed by the loader's client entry point. */
    interface Client {
        /** Whether the server we are connected to has Sipher and relays captions. */
        boolean serverHasSipher();

        void sendToServer(CaptionUpdatePayload update);
    }
}
