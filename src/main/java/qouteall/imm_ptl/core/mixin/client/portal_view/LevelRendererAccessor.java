package qouteall.imm_ptl.core.mixin.client.portal_view;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("levelRenderState")
    LevelRenderState ip_getLevelRenderState();
    
    @Mutable
    @Accessor("levelRenderState")
    void ip_setLevelRenderState(LevelRenderState state);
}
