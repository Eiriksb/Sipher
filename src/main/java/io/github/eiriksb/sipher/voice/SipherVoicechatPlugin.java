package io.github.eiriksb.sipher.voice;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import io.github.eiriksb.sipher.Sipher;
import io.github.eiriksb.sipher.server.CaptionRelay;
import io.github.eiriksb.sipher.server.VoiceState;

/**
 * Sipher's Simple Voice Chat plugin.
 *
 * <p>Client side it receives the local player's own microphone audio ({@link ClientSoundEvent}, raw 48 kHz PCM before
 * Opus encoding). Server side it only tracks the voice chat API and whisper state, to route captions; the server never
 * decodes or processes audio.
 */
@ForgeVoicechatPlugin
public final class SipherVoicechatPlugin implements VoicechatPlugin {
    /** Installed by the client when its caption engine starts; stays a no-op on dedicated servers. */
    public interface LocalAudioSink {
        void accept(short[] pcm, boolean whispering);
    }

    private static volatile LocalAudioSink localAudio = (pcm, whispering) -> {
    };

    public static void setLocalAudioSink(LocalAudioSink sink) {
        localAudio = sink;
    }

    @Override
    public String getPluginId() {
        return Sipher.MOD_ID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientSoundEvent.class, event -> localAudio.accept(event.getRawAudio(), event.isWhispering()));
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> VoiceState.setApi(event.getVoicechat()));
        registration.registerEvent(MicrophonePacketEvent.class, event -> {
            VoicechatConnection sender = event.getSenderConnection();
            if (sender != null) {
                VoiceState.microphonePacket(sender.getPlayer().getUuid(), event.getPacket().isWhispering());
            }
        });
        registration.registerEvent(PlayerDisconnectedEvent.class, event -> CaptionRelay.forget(event.getPlayerUuid()));
    }
}
