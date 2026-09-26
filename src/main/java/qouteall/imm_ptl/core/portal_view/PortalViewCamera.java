package qouteall.imm_ptl.core.portal_view;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.mixin.client.portal_view.CameraAccessor;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

/**
 * The camera of a portal view: the main camera transformed by the portal.
 * It is only used for extraction (culling, entity and particle extraction, fog, camera render state).
 */
@Environment(EnvType.CLIENT)
public final class PortalViewCamera extends Camera {

    public void setupFor(
        Camera mainCamera, Portal portal, ClientLevel mainLevel, ClientLevel destLevel,
        Entity cameraEntity, DeltaTracker deltaTracker
    ) {
        CameraAccessor self = (CameraAccessor) (Object) this;
        CameraAccessor main = (CameraAccessor) mainCamera;
        // Camera.tick() maintains these; this camera is never ticked
        self.ip_setFovModifier(main.ip_getFovModifier());
        self.ip_setOldFovModifier(main.ip_getOldFovModifier());
        self.ip_setEyeHeight(main.ip_getEyeHeight());
        self.ip_setEyeHeightOld(main.ip_getEyeHeightOld());

        // same placement, fov and projection as the main camera
        setLevel(mainLevel);
        setEntity(cameraEntity);
        update(deltaTracker);

        Vec3 position = portal.transformPoint(mainCamera.position());

        DQuaternion rotation = portal.getRotationD();
        Vec3 forward = rotation.rotate(new Vec3(mainCamera.forwardVector()));
        // Minecraft: forward = (-sin(yaw) cos(pitch), -sin(pitch), cos(yaw) cos(pitch)), angles in degrees
        float yRot = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
        float xRot = (float) Math.toDegrees(Math.asin(Mth.clamp(-forward.y, -1.0, 1.0)));

        setLevel(destLevel);
        setPosition(position);
        setRotation(yRot, xRot);

        self.ip_prepareCullFrustum(
            getViewRotationMatrix(new Matrix4f()), self.ip_createProjectionMatrixForCulling(), position
        );
    }
}
