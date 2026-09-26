package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.profiling.Profiler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.animation.ClientPortalAnimationManagement;
import qouteall.imm_ptl.core.portal.animation.StableClientTimer;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;

/**
 * Per-frame work that must happen before the camera is set up for the frame.
 * <p>
 * Before 26.x this ran at the head of {@code GameRenderer.render} (in the render mixin that is now
 * quarantined). In 26.x the camera is updated in {@code GameRenderer.update} and the frame is extracted
 * afterwards, so this runs right before {@code GameRenderer.update}.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft_Frame {
    @Shadow
    public @Nullable ClientLevel level;

    @Shadow
    @Final
    private DeltaTracker.Timer deltaTracker;

    @Inject(
        method = "renderFrame(Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V"
        )
    )
    private void ip_beforeFrame(boolean advanceGameTime, CallbackInfo ci) {
        Profiler.get().push("ip_pre_total_render");
        IPGlobal.PRE_TOTAL_RENDER_TASK_LIST.processTasks();
        Profiler.get().pop();

        // when the game time does not advance (e.g. while respawning), the world is not rendered
        if (level == null || !advanceGameTime) {
            return;
        }

        Profiler.get().push("ip_pre_render");
        // the partial tick, not the frame delta
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        RenderStates.updatePreRenderInfo(partialTick);
        StableClientTimer.update(level.getGameTime(), partialTick);
        ClientPortalAnimationManagement.update(); // must update before teleportation
        // teleport the camera through a portal in the frame in which it crosses the portal,
        // not only in the next tick
        ClientTeleportationManager.manageTeleportation(false);
        IPGlobal.PRE_GAME_RENDER_EVENT.invoker().run();
        PortalRenderer.switchToCorrectRenderer();
        Profiler.get().pop();
        
        if (IPCGlobal.lateClientLightUpdate && ClientWorldLoader.getIsInitialized()) {
            // vanilla runs ClientLevel.update() (apply received light data, propagate light changes)
            // every frame, but only for the player's level
            Profiler.get().push("ip_remote_world_light");
            for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
                if (world != level) {
                    ClientWorldLoader.withSwitchedWorld(world, world::update);
                }
            }
            Profiler.get().pop();
        }

        RenderStates.frameIndex++;
    }
}
