package qouteall.imm_ptl.core.compat.iris_portal_view.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.iris_portal_view.IrisPortalClipping;

import java.util.Map;
import java.util.Set;

/**
 * G-buffer programs of a shader pack clip portal views (see {@link IrisPortalClipping}).
 * {@code ShaderCreator.create} builds the G-buffer programs of both render paths (vanilla and Sodium terrain);
 * shadow programs are left alone, so the destination world still casts shadows from behind the portal plane.
 */
@Mixin(value = ShaderCreator.class, remap = false)
public class MixinIrisShaderCreator {
    @WrapOperation(
        method = "create",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/transform/TransformPatcher;patchSodium(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/gl/blending/AlphaTest;Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;Ljava/util/Set;Z)Ljava/util/Map;"
        )
    )
    private static Map<PatchShaderType, String> ip_clipSodium(
        String name, String vertex, String geometry, String tessControl, String tessEval, String fragment,
        AlphaTest alpha, Object2ObjectMap<?, ?> textureMap, Set<?> textureOverrides, boolean shadow,
        Operation<Map<PatchShaderType, String>> original,
        @Local(argsOnly = true, ordinal = 2) boolean isShadowPass
    ) {
        Map<PatchShaderType, String> result = original.call(
            name, vertex, geometry, tessControl, tessEval, fragment, alpha, textureMap, textureOverrides, shadow
        );
        return shadow || isShadowPass ? result : IrisPortalClipping.patch(name, result);
    }

    @WrapOperation(
        method = "create",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/transform/TransformPatcher;patchVanilla(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/gl/blending/AlphaTest;ZZZLnet/irisshaders/iris/gl/state/ShaderAttributeInputs;Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;Ljava/util/Set;)Ljava/util/Map;"
        )
    )
    private static Map<PatchShaderType, String> ip_clipVanilla(
        String name, String vertex, String geometry, String tessControl, String tessEval, String fragment,
        AlphaTest alpha, boolean isLines, boolean isClouds, boolean hasChunkOffset, ShaderAttributeInputs inputs,
        Object2ObjectMap<?, ?> textureMap, Set<?> textureOverrides,
        Operation<Map<PatchShaderType, String>> original,
        @Local(argsOnly = true, ordinal = 2) boolean isShadowPass
    ) {
        Map<PatchShaderType, String> result = original.call(
            name, vertex, geometry, tessControl, tessEval, fragment, alpha, isLines, isClouds, hasChunkOffset, inputs,
            textureMap, textureOverrides
        );
        return isShadowPass ? result : IrisPortalClipping.patch(name, result);
    }
}
