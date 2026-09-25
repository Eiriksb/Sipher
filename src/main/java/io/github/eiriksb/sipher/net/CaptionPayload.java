package io.github.eiriksb.sipher.net;

import io.github.eiriksb.sipher.Sipher;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Server → client: another player's caption, sent only to players who could hear that player. */
public record CaptionPayload(UUID speaker, int line, boolean partial, String language, String text, String english)
        implements CustomPacketPayload {
    public static final Type<CaptionPayload> TYPE = new Type<>(Sipher.id("caption"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CaptionPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, CaptionPayload::speaker,
            ByteBufCodecs.VAR_INT, CaptionPayload::line,
            ByteBufCodecs.BOOL, CaptionPayload::partial,
            ByteBufCodecs.stringUtf8(16), CaptionPayload::language,
            ByteBufCodecs.stringUtf8(CaptionText.MAX_WIRE_LENGTH), CaptionPayload::text,
            ByteBufCodecs.stringUtf8(CaptionText.MAX_WIRE_LENGTH), CaptionPayload::english,
            CaptionPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
