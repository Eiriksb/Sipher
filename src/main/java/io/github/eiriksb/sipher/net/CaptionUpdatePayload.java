package io.github.eiriksb.sipher.net;

import io.github.eiriksb.sipher.Sipher;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: a caption of the sender's own speech.
 *
 * @param line     per-speaker line id; partial updates and the final caption of one utterance share it
 * @param partial  true while the speaker is still talking
 * @param language language the speaker spoke
 * @param text     transcript in {@code language}
 * @param english  English translation, empty when {@code language} is English or no translation was available
 */
public record CaptionUpdatePayload(int line, boolean partial, String language, String text, String english)
        implements CustomPacketPayload {
    public static final Type<CaptionUpdatePayload> TYPE = new Type<>(Sipher.id("caption_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CaptionUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CaptionUpdatePayload::line,
            ByteBufCodecs.BOOL, CaptionUpdatePayload::partial,
            ByteBufCodecs.stringUtf8(16), CaptionUpdatePayload::language,
            ByteBufCodecs.stringUtf8(CaptionText.MAX_WIRE_LENGTH), CaptionUpdatePayload::text,
            ByteBufCodecs.stringUtf8(CaptionText.MAX_WIRE_LENGTH), CaptionUpdatePayload::english,
            CaptionUpdatePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
