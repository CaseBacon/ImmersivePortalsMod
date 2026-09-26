package qouteall.imm_ptl.core.mixin.client.portal_view;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.portal_view.PortalViewRenderer;

@Mixin(GameRenderer.class)
public class MixinGameRenderer_PortalView {
    // portal views are extracted together with the main level
    @Inject(
        method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterLevelExtraction(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        PortalViewRenderer.extractViews(deltaTracker);
    }
    
    // and drawn right after the main level, before the hand and the 3D HUD
    @Inject(
        method = "renderLevel()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterLevelRendering(CallbackInfo ci) {
        PortalViewRenderer.renderViews();
    }
}
