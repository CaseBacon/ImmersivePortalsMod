package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

/**
 * Everything Immersive Portals needs from Iris. The default invoker is used when Iris is absent;
 * {@link IrisInterfaceOnPresent} (the only active class that references Iris) is installed when it is loaded.
 * <p>
 * With a shader pack in use, Iris renders each level with its own targets, shadow pass and camera uniforms
 * taken from the main camera, so portal views are not drawn then (see {@code PortalViewRenderer}).
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
    }

    public static Invoker invoker = new Invoker();
}
