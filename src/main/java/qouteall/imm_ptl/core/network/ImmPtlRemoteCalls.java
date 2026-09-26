package qouteall.imm_ptl.core.network;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.chunk_loading.PerformanceLevel;
import qouteall.imm_ptl.core.commands.ClientDebugCommand;
import qouteall.imm_ptl.core.commands.PortalCommand;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import qouteall.q_misc_util.ImplRemoteProcedureCall;


/**
 * The allowlist of Immersive Portals core remote procedure calls.
 * Peripheral calls are registered in {@code PeripheralModMain}.
 */
public class ImmPtlRemoteCalls {
    
    /**
     * Client to server handlers. Called during common initialization on both sides.
     */
    public static void registerServerbound() {
        ImplRemoteProcedureCall.registerEnumCodec(PerformanceLevel.class);
        
        ImplRemoteProcedureCall.registerServerbound(BlockManipulationServer.RemoteCallables.class, "processPlayerActionPacket");
        ImplRemoteProcedureCall.registerServerbound(BlockManipulationServer.RemoteCallables.class, "processUseItemOnPacket");
        ImplRemoteProcedureCall.registerServerbound(ImmPtlChunkTracking.RemoteCallables.class, "acceptClientPerformanceInfo");
    }
    
    /**
     * Server to client handlers. Only called on the client, as the handler classes are client-only.
     */
    @Environment(EnvType.CLIENT)
    public static void registerClientbound() {
        ImplRemoteProcedureCall.registerClientbound(ClientWorldLoader.RemoteCallables.class, "checkBiomeRegistry");
        
        ImplRemoteProcedureCall.registerClientbound(ClientDebugCommand.RemoteCallables.class, "reportClientChunkLoadStatus");
        ImplRemoteProcedureCall.registerClientbound(ClientDebugCommand.RemoteCallables.class, "reportClientPlayerStatus");
        ImplRemoteProcedureCall.registerClientbound(ClientDebugCommand.RemoteCallables.class, "doListPortals");
        ImplRemoteProcedureCall.registerClientbound(ClientDebugCommand.RemoteCallables.class, "reportResourceConsumption");
        ImplRemoteProcedureCall.registerClientbound(ClientDebugCommand.RemoteCallables.class, "setNoFog");
        
        ImplRemoteProcedureCall.registerClientbound(PortalCommand.RemoteCallables.class, "clientAccelerate");
        ImplRemoteProcedureCall.registerClientbound(TransformationManager.RemoteCallables.class, "enableIsometricView");
        ImplRemoteProcedureCall.registerClientbound(TransformationManager.RemoteCallables.class, "disableIsometricView");
        ImplRemoteProcedureCall.registerClientbound(ClientTeleportationManager.RemoteCallables.class, "updateEntityPos");
    }
}
