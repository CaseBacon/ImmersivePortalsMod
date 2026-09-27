package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import qouteall.imm_ptl.core.compat.iris_portal_view.IrisPortalClipping;

/**
 * Only loaded when Iris is present. Uses Iris' API and its public static state; the portal view clipping of
 * shader packs is added to Iris' G-buffer programs by the mixins in {@code compat.iris_portal_view.mixin}.
 */
@Environment(EnvType.CLIENT)
public class IrisInterfaceOnPresent extends IrisInterface.Invoker {

    @Override
    public boolean isIrisPresent() {
        return true;
    }

    @Override
    public boolean isShaders() {
        // the loaded pack; IrisApi.isShaderPackInUse depends on the pipeline of the last rendered dimension
        return Iris.getCurrentPack().isPresent();
    }

    @Override
    public boolean isRenderingShadowMap() {
        return IrisApi.getInstance().isRenderingShadowPass();
    }

    @Override
    public @Nullable String getShaderpackName() {
        return isShaders() ? Iris.getCurrentPackName() : null;
    }
    
    @Override
    public void setShaderClipping(@Nullable Vector4fc coefficients) {
        IrisPortalClipping.setCoefficients(coefficients);
    }
}
