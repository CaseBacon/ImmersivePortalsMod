package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;

@Mixin(GameRenderer.class)
public class MixinGameRenderer_MultiWorld {
    @Shadow
    @Final
    private Minecraft minecraft;

    // vanilla only resizes the player's level renderer (entity outline target, occlusion graph)
    @Inject(method = "resize(II)V", at = @At("RETURN"))
    private void ip_onResize(int width, int height, CallbackInfo ci) {
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }
        for (LevelRenderer levelRenderer : ClientWorldLoader.WORLD_RENDERER_MAP.values()) {
            if (levelRenderer != minecraft.levelRenderer) {
                levelRenderer.resize(width, height);
            }
        }
    }
}
