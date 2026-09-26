package io.github.eiriksb.sipher.net;

import io.github.eiriksb.sipher.server.CaptionRelay;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.function.Consumer;

public final class SipherNetwork {
    /**
     * Never change this within a major version: NeoForge refuses the connection when both sides have the channel with
     * different versions, even though it is optional. Add new payload types instead (see docs/COMPATIBILITY.md).
     */
    private static final String PROTOCOL = "1";

    /** Set on the physical client; the dedicated server has no caption display. */
    private static Consumer<CaptionPayload> clientHandler = payload -> {
    };

    private SipherNetwork() {
    }

    public static void setClientHandler(Consumer<CaptionPayload> handler) {
        clientHandler = handler;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // Optional: vanilla-compatible in both directions, so Sipher clients can join servers without it (captions
        // then stay local) and players without Sipher can join servers that have it.
        PayloadRegistrar registrar = event.registrar(PROTOCOL).optional();
        registrar.playToServer(CaptionUpdatePayload.TYPE, CaptionUpdatePayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                CaptionRelay.handle(player, payload);
            }
        });
        registrar.playToClient(CaptionPayload.TYPE, CaptionPayload.STREAM_CODEC, (payload, context) -> clientHandler.accept(payload));
    }
}
