package io.github.eiriksb.sipher.api;

import io.github.eiriksb.sipher.config.SipherServerConfig;
import io.github.eiriksb.sipher.net.CaptionPayload;
import io.github.eiriksb.sipher.net.CaptionText;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Captions for things that aren't players, such as a talking NPC: shown to the chosen players as a bubble above the
 * entity and in their transcript, exactly like a player's caption, and translated into each player's reading language
 * when they have its pack. Captions in English (or with an English translation) translate for everyone. Part of
 * Sipher's public API for server mods; stays compatible within a major version.
 */
public final class SipherCaptions {
    private SipherCaptions() {
    }

    /**
     * Shows a caption to {@code listeners} (players without Sipher are skipped). Server thread.
     *
     * @param speaker  the entity saying it; the bubble floats above it and the transcript uses its name
     * @param line     id of this utterance for {@code speaker}: live updates and the final caption share it, and newer
     *                 utterances need higher ids
     * @param partial  true while the entity is still saying it
     * @param language the language {@code text} is in, like {@code en} or {@code de}
     * @param text     what is said
     * @param english  English translation, or empty when {@code text} is English or there is none
     */
    public static void show(Entity speaker, int line, boolean partial, String language, String text, String english,
                            Iterable<ServerPlayer> listeners) {
        int maxLength = SipherServerConfig.MAX_TEXT_LENGTH.get();
        CaptionPayload caption = new CaptionPayload(speaker.getUUID(), line, partial, CaptionText.language(language),
                CaptionText.sanitize(text, maxLength), CaptionText.sanitize(english, maxLength));
        for (ServerPlayer listener : listeners) {
            // NeoForge refuses to send a payload to a client without the channel.
            if (listener.connection.hasChannel(CaptionPayload.TYPE)) {
                PacketDistributor.sendToPlayer(listener, caption);
            }
        }
    }
}
