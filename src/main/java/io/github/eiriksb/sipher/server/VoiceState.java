package io.github.eiriksb.sipher.server;

import de.maxhenkel.voicechat.api.VoicechatServerApi;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the server knows about each player's voice from Simple Voice Chat: its API, and whether the player's last
 * microphone packet was a whisper. Whisper state comes from the server's own view of the audio, not from the client.
 */
public final class VoiceState {
    private static volatile VoicechatServerApi api;
    private static final Map<UUID, Boolean> WHISPERING = new ConcurrentHashMap<>();

    private VoiceState() {
    }

    public static VoicechatServerApi api() {
        return api;
    }

    public static void setApi(VoicechatServerApi serverApi) {
        api = serverApi;
    }

    public static void microphonePacket(UUID player, boolean whispering) {
        WHISPERING.put(player, whispering);
    }

    public static boolean whispering(UUID player) {
        return WHISPERING.getOrDefault(player, false);
    }

    public static void forget(UUID player) {
        WHISPERING.remove(player);
    }
}
