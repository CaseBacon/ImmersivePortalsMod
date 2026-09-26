package qouteall.imm_ptl.core.network;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.peripheral.PeripheralRemoteCalls;
import qouteall.q_misc_util.ImplRemoteProcedureCall;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every registration must resolve to an existing method with codecs for all parameters,
 * and every method id that the code invokes by name must be registered in the right direction.
 */
public class RemoteCallRegistrationTest {

    @BeforeAll
    public static void registerAll() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ImmPtlRemoteCalls.registerServerbound();
        ImmPtlRemoteCalls.registerClientbound();
        PeripheralRemoteCalls.registerServerbound();
        PeripheralRemoteCalls.registerClientbound();
    }

    // method ids used by McRemoteProcedureCall.tellServerToInvoke call sites
    private static final List<String> SERVERBOUND = List.of(
        "qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer.RemoteCallables.processPlayerActionPacket",
        "qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer.RemoteCallables.processUseItemOnPacket",
        "qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking.RemoteCallables.acceptClientPerformanceInfo",
        "qouteall.imm_ptl.peripheral.dim_stack.DimStackManagement.RemoteCallables.serverRemoveDimStack",
        "qouteall.imm_ptl.peripheral.dim_stack.DimStackManagement.RemoteCallables.serverSetupDimStack",
        "qouteall.imm_ptl.peripheral.wand.PortalWandInteraction.RemoteCallables.clearPortalClipboard",
        "qouteall.imm_ptl.peripheral.wand.PortalWandInteraction.RemoteCallables.confirmCopyCut",
        "qouteall.imm_ptl.peripheral.wand.PortalWandInteraction.RemoteCallables.copyCutPortal",
        "qouteall.imm_ptl.peripheral.wand.PortalWandInteraction.RemoteCallables.finishDragging",
        "qouteall.imm_ptl.peripheral.wand.PortalWandInteraction.RemoteCallables.finishPortalCreation",
        "qouteall.imm_ptl.peripheral.wand.PortalWandInteraction.RemoteCallables.requestApplyDrag",
        "qouteall.imm_ptl.peripheral.wand.PortalWandInteraction.RemoteCallables.undoDrag"
    );

    // method ids used by McRemoteProcedureCall.tellClientToInvoke call sites
    private static final List<String> CLIENTBOUND = List.of(
        "qouteall.imm_ptl.core.ClientWorldLoader.RemoteCallables.checkBiomeRegistry",
        "qouteall.imm_ptl.core.commands.ClientDebugCommand.RemoteCallables.doListPortals",
        "qouteall.imm_ptl.core.commands.ClientDebugCommand.RemoteCallables.reportClientChunkLoadStatus",
        "qouteall.imm_ptl.core.commands.ClientDebugCommand.RemoteCallables.reportClientPlayerStatus",
        "qouteall.imm_ptl.core.commands.ClientDebugCommand.RemoteCallables.reportResourceConsumption",
        "qouteall.imm_ptl.core.commands.ClientDebugCommand.RemoteCallables.setNoFog",
        "qouteall.imm_ptl.core.commands.PortalCommand.RemoteCallables.clientAccelerate",
        "qouteall.imm_ptl.core.render.TransformationManager.RemoteCallables.disableIsometricView",
        "qouteall.imm_ptl.core.render.TransformationManager.RemoteCallables.enableIsometricView",
        "qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.RemoteCallables.updateEntityPos",
        "qouteall.imm_ptl.peripheral.dim_stack.DimStackManagement.RemoteCallables.clientOpenScreen"
    );

    @Test
    public void callSitesAreRegistered() {
        for (String id : SERVERBOUND) {
            assertTrue(ImplRemoteProcedureCall.isServerboundRegistered(id), "serverbound not registered: " + id);
        }
        for (String id : CLIENTBOUND) {
            assertTrue(ImplRemoteProcedureCall.isClientboundRegistered(id), "clientbound not registered: " + id);
        }
    }

    @Test
    public void clientMethodsAreNotCallableByClients() {
        for (String id : CLIENTBOUND) {
            assertTrue(!ImplRemoteProcedureCall.isServerboundRegistered(id), "clientbound method is serverbound too: " + id);
        }
    }
}
