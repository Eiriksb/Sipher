package io.github.eiriksb.sipher.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.eiriksb.sipher.config.SipherClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
//? if >=1.21.11 {
/*import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
*///?} else
import net.minecraft.client.renderer.RenderType;
//? if >=1.21.9 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.CameraRenderState;
*///?} else
import net.minecraft.client.renderer.MultiBufferSource;

import java.util.ArrayList;
import java.util.List;

/** Draws caption lines above an entity's (usually a player's) name tag, in the name tag's coordinate space. */
public final class BubbleRenderer {
    private static final int LINE_SPACING = 12;
    private static final int PADDING_X = 4;
    private static final int PADDING_Y = 2;
    private static final float NAME_TAG_SCALE = 0.025F;
    private static final float ABOVE_NAME_TAG_PIXELS = 14.0F;
    /** Packed light for full brightness, the same value on every Minecraft version. */
    private static final int FULL_BRIGHT = 0xF000F0;

    private BubbleRenderer() {
    }

    /** One entity's caption lines, and where its name tag sits relative to the entity's position. */
    public record Bubble(List<RenderedLine> lines, double x, double y, double z) {
    }

    /** Lays out an entity's captions, or returns null when there is nothing to draw. */
    static Bubble prepare(Entity entity, List<CaptionStore.View> captions, Font font, float partialTick) {
        List<RenderedLine> lines = layout(captions, font);
        if (lines.isEmpty()) {
            return null;
        }
        Vec3 anchor = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick));
        return anchor == null ? new Bubble(lines, 0, entity.getBbHeight() + 0.5, 0)
                : new Bubble(lines, anchor.x, anchor.y + 0.5, anchor.z);
    }

    //? if >=1.21.9 {
    /*// Minecraft 1.21.9 and newer collect what to draw first and draw it later.
    public static void submit(PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, Bubble bubble,
                              int packedLight) {
        Font font = Minecraft.getInstance().font;
        int light = packedLight == 0 ? FULL_BRIGHT : packedLight;
        pose.pushPose();
        transform(pose, bubble, camera.orientation);
        float top = top(bubble);
        for (int i = 0; i < bubble.lines().size(); i++) {
            RenderedLine line = bubble.lines().get(i);
            float y = top + i * LINE_SPACING;
            int width = font.width(line.text());
            float x = -width / 2.0F;
            //? if >=26.3 {
            /^collector.submitTextBackground(pose, x - PADDING_X, y - PADDING_Y, x + width + PADDING_X,
                    y + font.lineHeight + PADDING_Y, backgroundColor(line), Font.DisplayMode.NORMAL, light);
            ^///?} else {
            int background = backgroundColor(line);
            collector.submitCustomGeometry(pose, textBackground(), (matrices, vertices) -> quad(matrices.pose(), vertices,
                    x - PADDING_X, y - PADDING_Y, x + width + PADDING_X, y + font.lineHeight + PADDING_Y, background, light));
            //?}
            // A later batch than the background, which would otherwise be drawn over the text
            collector.order(1).submitText(pose, x, y, line.text(), false, Font.DisplayMode.SEE_THROUGH, light, textColor(line), 0, 0);
        }
        pose.popPose();
    }
    *///?} else {
    /** Draws a bubble with {@code pose} at the entity's position. */
    public static void render(PoseStack pose, MultiBufferSource buffers, Bubble bubble, int packedLight) {
        Font font = Minecraft.getInstance().font;
        int light = packedLight == 0 ? FULL_BRIGHT : packedLight;
        pose.pushPose();
        transform(pose, bubble, Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        Matrix4f matrix = pose.last().pose();
        float top = top(bubble);
        for (int i = 0; i < bubble.lines().size(); i++) {
            RenderedLine line = bubble.lines().get(i);
            float y = top + i * LINE_SPACING;
            int width = font.width(line.text());
            float x = -width / 2.0F;
            quad(matrix, buffers.getBuffer(textBackground()), x - PADDING_X, y - PADDING_Y, x + width + PADDING_X,
                    y + font.lineHeight + PADDING_Y, backgroundColor(line), light);
            font.drawInBatch(line.text(), x, y, textColor(line), false, matrix, buffers, Font.DisplayMode.SEE_THROUGH, 0, light);
        }
        pose.popPose();
    }
    //?}

    /** From the entity's position to text space above its name tag, facing the camera. */
    private static void transform(PoseStack pose, Bubble bubble, org.joml.Quaternionf cameraOrientation) {
        pose.translate(bubble.x(), bubble.y(), bubble.z());
        pose.mulPose(new Matrix4f().rotation(cameraOrientation));
        float scale = NAME_TAG_SCALE * SipherClientConfig.BUBBLE_SCALE.get().floatValue();
        pose.scale(scale, -scale, scale);
    }

    private static float top(Bubble bubble) {
        return -ABOVE_NAME_TAG_PIXELS - bubble.lines().size() * LINE_SPACING;
    }

    //? if <26.3 {
    private static RenderType textBackground() {
        //? if >=1.21.11 {
        /*return RenderTypes.textBackground();
        *///?} else
        return RenderType.textBackground();
    }
    //?}

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

    private static int textColor(RenderedLine line) {
        int rgb = parseColor(SipherClientConfig.BUBBLE_TEXT_COLOR.get(), 0xFFFFFF);
        // Live captions are dimmed slightly so it is clear they may still change.
        return withAlpha(rgb, Math.max(0.3F, line.alpha() * (line.partial() ? 0.75F : 1F)));
    }

    private static int backgroundColor(RenderedLine line) {
        float alpha = SipherClientConfig.BUBBLE_BACKGROUND_OPACITY.get().floatValue() * line.alpha();
        return withAlpha(parseColor(SipherClientConfig.BUBBLE_BACKGROUND_COLOR.get(), 0x000000), alpha);
    }

    private static void quad(Matrix4f matrix, VertexConsumer builder, float left, float top, float right, float bottom, int argb, int light) {
        float a = (argb >>> 24) / 255F;
        float r = ((argb >> 16) & 0xFF) / 255F;
        float g = ((argb >> 8) & 0xFF) / 255F;
        float b = (argb & 0xFF) / 255F;
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

    record RenderedLine(FormattedCharSequence text, float alpha, boolean partial) {
    }
}
