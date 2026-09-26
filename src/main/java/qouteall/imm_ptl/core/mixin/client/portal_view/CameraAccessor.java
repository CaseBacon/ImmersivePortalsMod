package qouteall.imm_ptl.core.mixin.client.portal_view;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraAccessor {
    // updated in Camera.tick(); a portal view camera copies them from the main camera
    @Accessor("fovModifier")
    float ip_getFovModifier();
    
    @Accessor("fovModifier")
    void ip_setFovModifier(float value);
    
    @Accessor("oldFovModifier")
    float ip_getOldFovModifier();
    
    @Accessor("oldFovModifier")
    void ip_setOldFovModifier(float value);
    
    @Accessor("eyeHeight")
    float ip_getEyeHeight();
    
    @Accessor("eyeHeight")
    void ip_setEyeHeight(float value);
    
    @Accessor("eyeHeightOld")
    float ip_getEyeHeightOld();
    
    @Accessor("eyeHeightOld")
    void ip_setEyeHeightOld(float value);
    
    // the direction vectors that setRotation derives from yaw and pitch (mutable, final fields)
    @Accessor("forwards")
    Vector3f ip_getForwards();
    
    @Accessor("up")
    Vector3f ip_getUp();
    
    @Accessor("left")
    Vector3f ip_getLeft();
    
    // a portal view camera is always "detached": the camera entity is not the viewer's body in the view
    @Accessor("detached")
    void ip_setDetached(boolean detached);

    @Invoker("prepareCullFrustum")
    void ip_prepareCullFrustum(Matrix4fc modelViewMatrix, Matrix4f projectionMatrixForCulling, Vec3 cameraPos);
    
    @Invoker("createProjectionMatrixForCulling")
    Matrix4f ip_createProjectionMatrixForCulling();
}
