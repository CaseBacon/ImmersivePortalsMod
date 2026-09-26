package qouteall.imm_ptl.core.mixin.client.portal_view;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * LevelRenderer creates its SkyRenderer lazily with the render target that is current at that moment.
 * When a level is first drawn inside a portal view, that is the view's offscreen target, and the sky of that
 * level would stay bound to it. Always draw the sky into the current target instead.
 */
@Mixin(SkyRenderer.class)
public class MixinSkyRenderer {
    @ModifyExpressionValue(
        method = "render",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;"
        )
    )
    private RenderTarget useCurrentRenderTarget(RenderTarget captured) {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }
}
