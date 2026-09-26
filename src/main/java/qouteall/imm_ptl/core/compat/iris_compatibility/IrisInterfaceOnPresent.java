package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import org.jspecify.annotations.Nullable;

/**
 * Only loaded when Iris is present. Uses Iris' API and its public static state only (no mixins into Iris).
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
}
