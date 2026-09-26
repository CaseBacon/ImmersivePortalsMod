package qouteall.imm_ptl.core.mixin.common;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class MixinLivingEntity {
    
    @Inject(method = "Lnet/minecraft/world/entity/LivingEntity;tick()V", at = @At("RETURN"))
    private void onTickEnded(CallbackInfo ci) {
        LivingEntity this_ = (LivingEntity) (Object) this;
        if (this_.getLastHurtByMob() != null) {
            if (this_.getLastHurtByMob().level() != this_.level()) {
            	this_.setLastHurtByMob(null);
            }
        }
        if (this_.getLastHurtMob() != null) {
            if (this_.getLastHurtMob().level() != this_.level()) {
                // 26.3: the pre-port code called setLastHurtByPlayer(null) here;
                // clearing the player reference keeps that behavior
                this_.setLastHurtByPlayer((Player) null, 0);
            }
        }
    }
}
