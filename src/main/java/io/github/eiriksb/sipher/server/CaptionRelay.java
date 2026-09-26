package io.github.eiriksb.sipher.server;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import io.github.eiriksb.sipher.api.PlayerCaptionEvent;
import io.github.eiriksb.sipher.config.SipherServerConfig;
import io.github.eiriksb.sipher.net.CaptionPayload;
import io.github.eiriksb.sipher.net.CaptionText;
import io.github.eiriksb.sipher.net.CaptionUpdatePayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forwards a player's captions to exactly the players who can hear that player on Simple Voice Chat (see
 * {@link CaptionRouting}) and who have Sipher. Captions are never broadcast to the whole server. Every accepted caption
 * is also posted as a {@link PlayerCaptionEvent} for other server mods.
 */
public final class CaptionRelay {
    private static final Map<UUID, RateLimiter> LIMITERS = new ConcurrentHashMap<>();

    private CaptionRelay() {
    }

    public static void handle(ServerPlayer speaker, CaptionUpdatePayload update) {
        RateLimiter limiter = LIMITERS.computeIfAbsent(speaker.getUUID(), id -> new RateLimiter());
        if (!limiter.tryAcquire(SipherServerConfig.MAX_UPDATES_PER_SECOND.get())) {
            return;
        }

        int maxLength = SipherServerConfig.MAX_TEXT_LENGTH.get();
        String text = CaptionText.sanitize(update.text(), maxLength);
        String english = CaptionText.sanitize(update.english(), maxLength);
        if (text.isEmpty() && english.isEmpty() && update.partial()) {
            return;
        }
        String language = CaptionText.language(update.language());
        // Other server mods get every accepted caption, even when relaying to players is switched off.
        NeoForge.EVENT_BUS.post(new PlayerCaptionEvent(speaker, update.line(), update.partial(), language, text, english));

        if (!SipherServerConfig.RELAY_ENABLED.get() || (update.partial() && !SipherServerConfig.RELAY_PARTIALS.get())) {
            return;
        }
        CaptionPayload caption = new CaptionPayload(speaker.getUUID(), update.line(), update.partial(), language, text, english);

        for (ServerPlayer listener : listeners(speaker)) {
            // NeoForge refuses to send a payload to a client without the channel: players without Sipher get nothing.
            if (listener.connection.hasChannel(CaptionPayload.TYPE)) {
                PacketDistributor.sendToPlayer(listener, caption);
            }
        }
    }

    public static void forget(UUID player) {
        LIMITERS.remove(player);
        VoiceState.forget(player);
    }

    static Set<ServerPlayer> listeners(ServerPlayer speaker) {
        VoicechatServerApi api = VoiceState.api();
        List<CaptionRouting.Voice<ServerPlayer>> players = new ArrayList<>();
        for (ServerPlayer player : speaker.server.getPlayerList().getPlayers()) {
            players.add(voice(api, player));
        }
        return CaptionRouting.listeners(voice(api, speaker), players, range(api, speaker));
    }

    private static CaptionRouting.Voice<ServerPlayer> voice(VoicechatServerApi api, ServerPlayer player) {
        CaptionRouting.Group group = null;
        // Without the voice chat server (not started yet, or disabled) fall back to plain proximity.
        boolean listening = api == null;
        if (api != null) {
            VoicechatConnection connection = api.getConnectionOf(player.getUUID());
            if (connection != null) {
                listening = connection.isConnected() && !connection.isDisabled();
                Group voiceGroup = connection.getGroup();
                if (voiceGroup != null) {
                    group = new CaptionRouting.Group(voiceGroup.getId(), groupType(voiceGroup.getType()));
                }
            }
        }
        return new CaptionRouting.Voice<>(player, player.level().dimension(), player.getX(), player.getY(), player.getZ(),
                group, listening);
    }

    private static CaptionRouting.GroupType groupType(Group.Type type) {
        if (type == Group.Type.OPEN) {
            return CaptionRouting.GroupType.OPEN;
        }
        return type == Group.Type.ISOLATED ? CaptionRouting.GroupType.ISOLATED : CaptionRouting.GroupType.NORMAL;
    }

    private static double range(VoicechatServerApi api, ServerPlayer speaker) {
        if (api == null) {
            return SipherServerConfig.FALLBACK_RANGE.get();
        }
        if (VoiceState.whispering(speaker.getUUID())) {
            return api.getServerConfig().getDouble("whisper_distance", api.getVoiceChatDistance() / 2);
        }
        return api.getVoiceChatDistance();
    }

    /** Token bucket: a steady {@code perSecond} with bursts of twice that. */
    private static final class RateLimiter {
        private double tokens = -1;
        private long lastNanos = System.nanoTime();

        synchronized boolean tryAcquire(int perSecond) {
            long now = System.nanoTime();
            double capacity = perSecond * 2.0;
            tokens = tokens < 0 ? capacity : Math.min(capacity, tokens + (now - lastNanos) / 1e9 * perSecond);
            lastNanos = now;
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        }
    }
}
