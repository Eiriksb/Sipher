package io.github.eiriksb.sipher.api;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * A player's caption of their own speech reached the server. Posted on {@code NeoForge.EVENT_BUS}, on the server
 * thread, for every caption the server accepts: after rate limiting and sanitising, and whether or not the relay to
 * other players is enabled. This is Sipher's public API for other mods; it stays compatible within a major version.
 *
 * <p>Live captions ({@link #isPartial()}) may still change; the final caption of an utterance has the same
 * {@link #getLine()}. A final caption with empty text means the utterance turned out to be noise.
 */
public final class PlayerCaptionEvent extends Event {
    private final ServerPlayer player;
    private final int line;
    private final boolean partial;
    private final String language;
    private final String text;
    private final String english;

    public PlayerCaptionEvent(ServerPlayer player, int line, boolean partial, String language, String text, String english) {
        this.player = player;
        this.line = line;
        this.partial = partial;
        this.language = language;
        this.text = text;
        this.english = english;
    }

    /** The player who spoke. */
    public ServerPlayer getPlayer() {
        return player;
    }

    /** Per-player line id; live updates and the final caption of one utterance share it. */
    public int getLine() {
        return line;
    }

    /** True while the player is still talking. */
    public boolean isPartial() {
        return partial;
    }

    /** Language the player spoke: a lowercase code such as {@code en} or {@code pt-br}, or {@code und} if unknown. */
    public String getLanguage() {
        return language;
    }

    /** What the player said, in {@link #getLanguage()}. */
    public String getText() {
        return text;
    }

    /** English translation; empty when the player spoke English or no translation was available. */
    public String getEnglish() {
        return english;
    }
}
