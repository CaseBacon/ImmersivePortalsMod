package qouteall.imm_ptl.core.mixin.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.ducks.IEParticleManager;

@SuppressWarnings("resource")
@Mixin(ParticleEngine.class)
public class MixinParticleEngine implements IEParticleManager {
    @Shadow
    protected ClientLevel level;
    
    // PORT(26.3): particle rendering moved to ParticleGroup extraction.
    // The per-dimension filters live in MixinParticleGroup and MixinQuadParticleGroup.
    // Skipping all particles when more than 4 portals are rendered returns with portal rendering.
    
    @Override
    public void ip_setWorld(ClientLevel world_) {
        level = world_;
    }
    
}
