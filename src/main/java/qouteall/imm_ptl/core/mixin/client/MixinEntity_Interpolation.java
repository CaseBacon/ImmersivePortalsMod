package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Since 26.x every entity interpolates through its {@code InterpolationHandler}, and all server
 * position updates go through {@link Entity#moveOrInterpolateTo(PositionPath, float, float, boolean)}.
 * This replaces the former lerpTo mixins of LivingEntity and AbstractMinecart.
 */
@Mixin(Entity.class)
public class MixinEntity_Interpolation {
    @Inject(
        method = "moveOrInterpolateTo(Lnet/minecraft/world/entity/PositionPath;FFZ)V",
        at = @At("RETURN")
    )
    private void onMoveOrInterpolateTo(
        @Nullable PositionPath position, float yRot, float xRot, boolean hasRotation, CallbackInfo ci
    ) {
        Entity this_ = (Entity) (Object) this;
        if (position == null || !this_.level().isClientSide()) {
            return;
        }
        Vec3 target = position.endPosition();
        
        // for debugging
        if (!IPGlobal.allowClientEntityPosInterpolation) {
            this_.getInterpolation().cancel();
            this_.setPos(target);
            return;
        }
        
        // avoid entity position interpolate when crossing portal to the same dimension
        Portal collidingPortal = ((IEEntity) this_).ip_getCollidingPortal();
        if (collidingPortal != null && this_.position().distanceToSqr(target) > 4) {
            this_.getInterpolation().cancel();
            McHelper.setPosAndLastTickPos(
                this_,
                target,
                target.subtract(McHelper.getWorldVelocity(this_))
            );
            McHelper.updateBoundingBox(this_);
        }
    }
}
