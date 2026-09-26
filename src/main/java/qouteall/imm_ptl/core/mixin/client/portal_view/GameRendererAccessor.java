package qouteall.imm_ptl.core.mixin.client.portal_view;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("resourcePool")
    CrossFrameResourcePool ip_getResourcePool();
    
    // the level renderer draws into GameRenderer.mainRenderTarget(); a portal view swaps it temporarily
    @Accessor("mainRenderTarget")
    RenderTarget ip_getMainRenderTarget();
    
    @Mutable
    @Accessor("mainRenderTarget")
    void ip_setMainRenderTarget(RenderTarget target);

    // Iris reads the camera for its uniforms and shadows from GameRenderer.mainCamera(); a portal view swaps in its own
    @Accessor("mainCamera")
    Camera ip_getMainCamera();

    @Mutable
    @Accessor("mainCamera")
    void ip_setMainCamera(Camera camera);

    // Sodium sets up the terrain with the fog of GameRenderer.fogRenderer; a portal view swaps in its own
    @Accessor("fogRenderer")
    FogRenderer ip_getFogRenderer();

    @Mutable
    @Accessor("fogRenderer")
    void ip_setFogRenderer(FogRenderer fogRenderer);
}
