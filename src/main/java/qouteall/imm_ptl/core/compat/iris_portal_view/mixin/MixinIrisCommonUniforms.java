package qouteall.imm_ptl.core.compat.iris_portal_view.mixin;

import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import net.irisshaders.iris.uniforms.CommonUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_portal_view.IrisPortalClipping;

/**
 * Registers the clip coefficients of portal views with every program that gets Iris' dynamic uniforms
 * (programs that do not declare the uniform ignore it).
 */
@Mixin(value = CommonUniforms.class, remap = false)
public class MixinIrisCommonUniforms {
    @Inject(method = "addDynamicUniforms", at = @At("RETURN"))
    private static void ip_addPortalClipping(DynamicUniformHolder uniforms, FogMode fogMode, CallbackInfo ci) {
        uniforms.uniform4f(IrisPortalClipping.UNIFORM, IrisPortalClipping::getCoefficients, IrisPortalClipping.NOTIFIER);
    }
}
