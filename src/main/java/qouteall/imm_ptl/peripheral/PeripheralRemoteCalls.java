package qouteall.imm_ptl.peripheral;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import qouteall.imm_ptl.peripheral.dim_stack.DimStackInfo;
import qouteall.imm_ptl.peripheral.dim_stack.DimStackManagement;
import qouteall.imm_ptl.peripheral.wand.PortalWandInteraction;
import qouteall.imm_ptl.peripheral.wand.ProtoPortal;
import qouteall.q_misc_util.ImplRemoteProcedureCall;

import java.util.List;

/**
 * Remote procedure call allowlist of the peripheral part (see {@link ImplRemoteProcedureCall}).
 */
public final class PeripheralRemoteCalls {
    private PeripheralRemoteCalls() {}
    
    public static void registerServerbound() {
        ImplRemoteProcedureCall.registerJsonCodec(ProtoPortal.class);
        ImplRemoteProcedureCall.registerJsonCodec(PortalWandInteraction.DraggingInfo.class);
        ImplRemoteProcedureCall.registerJsonCodec(DimStackInfo.class);
        for (String method : List.of(
            "finishPortalCreation", "requestApplyDrag", "undoDrag", "finishDragging",
            "copyCutPortal", "confirmCopyCut", "clearPortalClipboard"
        )) {
            ImplRemoteProcedureCall.registerServerbound(PortalWandInteraction.RemoteCallables.class, method);
        }
        ImplRemoteProcedureCall.registerServerbound(DimStackManagement.RemoteCallables.class, "serverSetupDimStack");
        ImplRemoteProcedureCall.registerServerbound(DimStackManagement.RemoteCallables.class, "serverRemoveDimStack");
    }
    
    @Environment(EnvType.CLIENT)
    public static void registerClientbound() {
        ImplRemoteProcedureCall.registerClientbound(DimStackManagement.RemoteCallables.class, "clientOpenScreen");
    }
}
