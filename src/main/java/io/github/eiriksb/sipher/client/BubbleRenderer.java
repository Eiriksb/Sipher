package io.github.eiriksb.sipher.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.eiriksb.sipher.config.SipherClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** Draws caption lines above a player's name tag, in the name tag's coordinate space. */
public final class BubbleRenderer {
    private static final int LINE_SPACING = 12;
    private static final int PADDING_X = 4;
    private static final int PADDING_Y = 2;
    private static final float NAME_TAG_SCALE = 0.025F;
    private static final float ABOVE_NAME_TAG_PIXELS = 14.0F;

    private BubbleRenderer() {
    }

    public static void render(PoseStack pose, MultiBufferSource buffers, Player player, List<CaptionStore.View> captions,
                              Font font, int packedLight, float partialTick) {
        List<RenderedLine> lines = layout(captions, font);
        if (lines.isEmpty()) {
            return;
        }

        Vec3 anchor = player.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, player.getViewYRot(partialTick));
        double y = anchor == null ? player.getBbHeight() + 0.5 : anchor.y + 0.5;

        pose.pushPose();
        pose.translate(anchor == null ? 0 : anchor.x, y, anchor == null ? 0 : anchor.z);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        float scale = NAME_TAG_SCALE * SipherClientConfig.BUBBLE_SCALE.get().floatValue();
        pose.scale(scale, -scale, scale);

        Matrix4f matrix = pose.last().pose();
        int light = packedLight == 0 ? LightTexture.FULL_BRIGHT : packedLight;
        float top = -ABOVE_NAME_TAG_PIXELS - lines.size() * LINE_SPACING;
        for (int i = 0; i < lines.size(); i++) {
            draw(font, lines.get(i), matrix, buffers, top + i * LINE_SPACING, light);
        }
        pose.popPose();
    }

    private static List<RenderedLine> layout(List<CaptionStore.View> captions, Font font) {
        List<RenderedLine> lines = new ArrayList<>();
        int width = SipherClientConfig.BUBBLE_WRAP_WIDTH.get();
        for (CaptionStore.View caption : captions) {
            for (FormattedCharSequence line : font.split(Component.literal(caption.text()), width)) {
                lines.add(new RenderedLine(line, caption.alpha(), caption.partial()));
            }
        }
        return lines;
    }

    private static void draw(Font font, RenderedLine line, Matrix4f matrix, MultiBufferSource buffers, float y, int light) {
        int width = font.width(line.text());
        float x = -width / 2.0F;
        int textRgb = parseColor(SipherClientConfig.BUBBLE_TEXT_COLOR.get(), 0xFFFFFF);
        // Live captions are dimmed slightly so it is clear they may still change.
        int text = withAlpha(textRgb, Math.max(0.3F, line.alpha() * (line.partial() ? 0.75F : 1F)));
        float backgroundAlpha = SipherClientConfig.BUBBLE_BACKGROUND_OPACITY.get().floatValue() * line.alpha();
        int background = withAlpha(parseColor(SipherClientConfig.BUBBLE_BACKGROUND_COLOR.get(), 0x000000), backgroundAlpha);

        quad(matrix, buffers, x - PADDING_X, y - PADDING_Y, x + width + PADDING_X, y + font.lineHeight + PADDING_Y, background, light);
        font.drawInBatch(line.text(), x, y, text, false, matrix, buffers, Font.DisplayMode.SEE_THROUGH, 0, light);
    }

    private static void quad(Matrix4f matrix, MultiBufferSource buffers, float left, float top, float right, float bottom, int argb, int light) {
        float a = (argb >>> 24) / 255F;
        float r = ((argb >> 16) & 0xFF) / 255F;
        float g = ((argb >> 8) & 0xFF) / 255F;
        float b = (argb & 0xFF) / 255F;
        VertexConsumer builder = buffers.getBuffer(RenderType.textBackground());
        builder.addVertex(matrix, left, bottom, 0).setColor(r, g, b, a).setLight(light);
        builder.addVertex(matrix, right, bottom, 0).setColor(r, g, b, a).setLight(light);
        builder.addVertex(matrix, right, top, 0).setColor(r, g, b, a).setLight(light);
        builder.addVertex(matrix, left, top, 0).setColor(r, g, b, a).setLight(light);
    }

    private static int withAlpha(int rgb, float alpha) {
        return (Math.round(Math.clamp(alpha, 0F, 1F) * 255F) << 24) | (rgb & 0xFFFFFF);
    }

    static int parseColor(String hex, int fallback) {
        String value = hex == null ? "" : hex.trim().replace("#", "");
        if (value.length() != 6) {
            return fallback;
        }
        try {
            return Integer.parseInt(value, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record RenderedLine(FormattedCharSequence text, float alpha, boolean partial) {
    }
}
