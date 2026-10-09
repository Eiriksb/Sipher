package io.github.eiriksb.sipher.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.eiriksb.sipher.client.BubbleRenderer;
import io.github.eiriksb.sipher.client.SipherClient;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.21.9 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.CameraRenderState;
*///?} else
import net.minecraft.client.renderer.MultiBufferSource;
//? if >=1.21.2
//import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * Caption bubbles above entities, drawn where the name tag would be. The injections are at every return: vanilla
 * returns early from these methods for entities without a visible name tag.
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {
    //? if >=1.21.2 {
    /*@Inject(method = "extractRenderState", at = @At("RETURN"))
    private void sipher$prepareCaptions(Entity entity, EntityRenderState state, float partialTick, CallbackInfo callback) {
        SipherClient.prepareBubble(state, entity, partialTick);
    }
    *///?}

    //? if >=1.21.9 {
    /*@Inject(method = "submit", at = @At("RETURN"))
    private void sipher$submitCaptions(EntityRenderState state, PoseStack pose, SubmitNodeCollector collector,
                                       CameraRenderState camera, CallbackInfo callback) {
        BubbleRenderer.Bubble bubble = SipherClient.preparedBubble(state);
        if (bubble != null) {
            BubbleRenderer.submit(pose, collector, camera, bubble, state.lightCoords);
        }
    }
    *///?} elif >=1.21.2 {
    /*@Inject(method = "render", at = @At("RETURN"))
    private void sipher$renderCaptions(EntityRenderState state, PoseStack pose, MultiBufferSource buffers, int packedLight,
                                       CallbackInfo callback) {
        BubbleRenderer.Bubble bubble = SipherClient.preparedBubble(state);
        if (bubble != null) {
            BubbleRenderer.render(pose, buffers, bubble, packedLight);
        }
    }
    *///?} else {
    @Inject(method = "render", at = @At("RETURN"))
    private void sipher$renderCaptions(Entity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers,
                                       int packedLight, CallbackInfo callback) {
        BubbleRenderer.Bubble bubble = SipherClient.bubble(entity, partialTick);
        if (bubble != null) {
            BubbleRenderer.render(pose, buffers, bubble, packedLight);
        }
    }
    //?}
}
