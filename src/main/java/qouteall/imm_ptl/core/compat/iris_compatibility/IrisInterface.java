package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * Everything Immersive Portals needs from Iris. The default invoker is used when Iris is absent;
 * {@link IrisInterfaceOnPresent} (the only active class that references Iris) is installed when it is loaded.
 * <p>
 * With a shader pack in use, Iris renders every {@code LevelRenderer.render} call through the pipeline of the
 * current dimension and takes its camera from {@code GameRenderer.mainCamera()}; {@code PortalViewRenderer} sets
 * both up for a portal view (see "Iris" in docs/26.3-port-progress.md).
 */
@Environment(EnvType.CLIENT)
public class IrisInterface {

    public static class Invoker {
        public boolean isIrisPresent() {
            return false;
        }

        /**
         * Whether a shader pack is loaded and enabled.
         */
        public boolean isShaders() {
            return false;
        }

        public boolean isRenderingShadowMap() {
            return false;
        }

        public @Nullable String getShaderpackName() {
            return null;
        }
        
        /**
         * Sets the portal view clipping of the shader pack's G-buffer programs ({@code ShaderClipPlane}), or
         * disables it with null. Only meaningful while {@link #isShaders()}.
         */
        public void setShaderClipping(@Nullable Vector4fc coefficients) {
        
        }
    }

    public static Invoker invoker = new Invoker();
}
