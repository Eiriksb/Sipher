package io.github.eiriksb.sipher.net;

import io.github.eiriksb.sipher.server.CaptionRelay;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * Sipher's two payloads, registered by each loader's entry point. Both are optional, so either side can be vanilla:
 * Sipher clients can join servers without it (captions then stay local) and players without Sipher can join servers
 * that have it. Handlers run on the main thread.
 */
public final class SipherNetwork {
    /**
     * Never change this within a major version: NeoForge refuses the connection when both sides have the channel with
     * different versions, even though it is optional. Add new payload types instead (see docs/COMPATIBILITY.md).
     */
    public static final String PROTOCOL = "1";

    /** Set on the physical client; the dedicated server has no caption display. */
    private static Consumer<CaptionPayload> clientHandler = payload -> {
    };

    private SipherNetwork() {
    }

    public static void setClientHandler(Consumer<CaptionPayload> handler) {
        clientHandler = handler;
    }

    /** A player sent a caption of their own speech. */
    public static void receivedOnServer(ServerPlayer player, CaptionUpdatePayload update) {
        CaptionRelay.handle(player, update);
    }

    /** The server relayed another player's caption. */
    public static void receivedOnClient(CaptionPayload caption) {
        clientHandler.accept(caption);
    }
}
