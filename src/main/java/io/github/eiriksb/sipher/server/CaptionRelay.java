package io.github.eiriksb.sipher.server;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import io.github.eiriksb.sipher.config.SipherServerConfig;
import io.github.eiriksb.sipher.net.CaptionPayload;
import io.github.eiriksb.sipher.net.CaptionText;
import io.github.eiriksb.sipher.net.CaptionUpdatePayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forwards a player's captions to exactly the players who can hear that player on Simple Voice Chat.
 *
 * <ul>
 *     <li>In a group: the group's members. Open groups are also heard by nearby players outside any group.</li>
 *     <li>Otherwise: players in the same dimension within voice range (whisper range while whispering).</li>
 * </ul>
 * Captions are never broadcast to the whole server.
 */
public final class CaptionRelay {
    private static final Map<UUID, RateLimiter> LIMITERS = new ConcurrentHashMap<>();

    private CaptionRelay() {
    }

    public static void handle(ServerPlayer speaker, CaptionUpdatePayload update) {
        if (!SipherServerConfig.RELAY_ENABLED.get() || (update.partial() && !SipherServerConfig.RELAY_PARTIALS.get())) {
            return;
        }
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
        CaptionPayload caption = new CaptionPayload(speaker.getUUID(), update.line(), update.partial(),
                CaptionText.language(update.language()), text, english);

        for (ServerPlayer listener : listeners(speaker)) {
            PacketDistributor.sendToPlayer(listener, caption);
        }
    }

    public static void forget(UUID player) {
        LIMITERS.remove(player);
        VoiceState.forget(player);
    }

    static Set<ServerPlayer> listeners(ServerPlayer speaker) {
        Set<ServerPlayer> listeners = new LinkedHashSet<>();
        VoicechatServerApi api = VoiceState.api();
        if (api == null) {
            addNearby(listeners, speaker, SipherServerConfig.FALLBACK_RANGE.get(), false);
            listeners.remove(speaker);
            return listeners;
        }

        VoicechatConnection connection = api.getConnectionOf(speaker.getUUID());
        Group group = connection == null ? null : connection.getGroup();
        if (group != null) {
            for (ServerPlayer player : speaker.server.getPlayerList().getPlayers()) {
                VoicechatConnection other = api.getConnectionOf(player.getUUID());
                Group otherGroup = other == null ? null : other.getGroup();
                if (otherGroup != null && otherGroup.getId().equals(group.getId())) {
                    listeners.add(player);
                }
            }
            if (group.getType() == Group.Type.OPEN) {
                addNearby(listeners, speaker, range(api, speaker), true);
            }
        } else {
            addNearby(listeners, speaker, range(api, speaker), false);
        }
        listeners.remove(speaker);
        return listeners;
    }

    private static double range(VoicechatServerApi api, ServerPlayer speaker) {
        if (VoiceState.whispering(speaker.getUUID())) {
            return api.getServerConfig().getDouble("whisper_distance", api.getVoiceChatDistance() / 2);
        }
        return api.getVoiceChatDistance();
    }

    private static void addNearby(Set<ServerPlayer> listeners, ServerPlayer speaker, double range, boolean ungroupedOnly) {
        VoicechatServerApi api = VoiceState.api();
        double rangeSquared = range * range;
        for (ServerPlayer player : speaker.serverLevel().players()) {
            if (player.distanceToSqr(speaker) > rangeSquared) {
                continue;
            }
            if (ungroupedOnly && api != null) {
                VoicechatConnection other = api.getConnectionOf(player.getUUID());
                if (other != null && other.isInGroup()) {
                    continue;
                }
            }
            listeners.add(player);
        }
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
