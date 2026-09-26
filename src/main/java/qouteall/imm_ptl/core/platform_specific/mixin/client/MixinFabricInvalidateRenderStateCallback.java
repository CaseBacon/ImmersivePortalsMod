package qouteall.imm_ptl.core.platform_specific.mixin.client;

import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;

@Pseudo
@Mixin(value = InvalidateRenderStateCallback.class, remap = false)
public interface MixinFabricInvalidateRenderStateCallback {
    @Inject(
        // the loop body of the array-backed invoker
        method = "lambda$static$1([Lnet/fabricmc/fabric/api/client/rendering/v1/InvalidateRenderStateCallback;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onInvokeEvent(InvalidateRenderStateCallback[] event, CallbackInfo ci) {
        if (ClientWorldLoader.getIsCreatingClientWorld()) {
            ci.cancel();
        }
    }
}
