package qouteall.imm_ptl.core.compat;

import net.fabricmc.loader.api.FabricLoader;
import qouteall.q_misc_util.Helper;

/**
 * Before 26.3 this toggled Porting Lib's stencil buffer flag for the stencil portal renderer.
 * The 26.3 renderer has no stencil path, so only the presence detection remains.
 */
public class IPPortingLibCompat {
    
    public static boolean isPortingLibPresent = false;
    
    public static void init() {
        if (FabricLoader.getInstance().isModLoaded("porting_lib")) {
            Helper.log("Porting Lib is present");
            isPortingLibPresent = true;
        }
    }
}
