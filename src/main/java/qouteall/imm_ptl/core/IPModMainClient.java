package qouteall.imm_ptl.core;

import qouteall.imm_ptl.core.network.ImmPtlRemoteCalls;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import qouteall.imm_ptl.core.collision.CollisionHelper;
import qouteall.imm_ptl.core.commands.ClientDebugCommand;
import qouteall.imm_ptl.core.compat.IPFlywheelCompat;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.miscellaneous.DubiousThings;
import qouteall.imm_ptl.core.miscellaneous.GcMonitor;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;
import qouteall.imm_ptl.core.network.ImmPtlNetworking;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.portal.animation.ClientPortalAnimationManagement;
import qouteall.imm_ptl.core.portal.animation.StableClientTimer;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import qouteall.q_misc_util.dimension.DimensionIntId;
import qouteall.q_misc_util.my_util.MyTaskList;

public class IPModMainClient {
    
    private static void showNvidiaVideoCardWarning() {
        IPGlobal.CLIENT_TASK_LIST.addTask(MyTaskList.withDelayCondition(
            () -> Minecraft.getInstance().level == null,
            MyTaskList.oneShotTask(() -> {
                if (IPMcHelper.isNvidiaVideocard()) {
                    if (!SodiumInterface.invoker.isSodiumPresent()) {
                        CHelper.printChat(
                            Component.translatable("imm_ptl.nvidia_warning")
                                .withStyle(ChatFormatting.RED)
                                .append(McHelper.getLinkText("https://github.com/CaffeineMC/sodium-fabric/issues/1486"))
                        );
                    }
                }
            })
        ));
    }
    
    private static void showQuiltWarning() {
        IPGlobal.CLIENT_TASK_LIST.addTask(MyTaskList.withDelayCondition(
            () -> Minecraft.getInstance().level == null,
            MyTaskList.oneShotTask(() -> {
                if (O_O.isQuilt()) {
                    if (IPConfig.getConfig().shouldDisplayWarning("quilt")) {
                        CHelper.printChat(
                            Component.translatable("imm_ptl.quilt_warning")
                                .append(IPMcHelper.getDisableWarningText("quilt"))
                        );
                    }
                }
            })
        ));
    }
    
    public static void init() {
        ImmPtlRemoteCalls.registerClientbound();
        
        ClientWorldLoader.init();
        
        ClientTeleportationManager.init();
        
        // PORT(26.3): the renderer initialisation (ShaderCodeTransformation, MyRenderHelper,
        // the stencil/framebuffer renderers, CrossPortalEntityRenderer, GLResourceCache,
        // CloudContext, SharedBlockMeshBuffers, VisibleSectionDiscovery, ImmPtlViewArea,
        // GuiPortalRendering, ForceMainThreadRebuild) is quarantined until the renderer phase.
        // IPCGlobal.renderer stays on the dummy renderer.
        
        DubiousThings.init();
        
        CollisionHelper.initClient();
        
        PortalRenderInfo.init();
        
        GcMonitor.initClient();
        
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            ClientDebugCommand.register(dispatcher);
        });
        
//        showIntelVideoCardWarning();
        
        showNvidiaVideoCardWarning();
        
        showQuiltWarning();
        
        StableClientTimer.init();
        
        ClientPortalAnimationManagement.init();
        
        IPFlywheelCompat.init();
        
        ImmPtlNetworking.initClient();
        ImmPtlNetworkConfig.initClient();
        
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(() -> {
            IPGlobal.CLIENT_TASK_LIST.forceClearTasks();
        });
        
        DimensionIntId.initClient();
    }
    
}
