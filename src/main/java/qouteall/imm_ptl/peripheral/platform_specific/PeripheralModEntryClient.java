package qouteall.imm_ptl.peripheral.platform_specific;

import net.fabricmc.api.ClientModInitializer;
import qouteall.imm_ptl.peripheral.PeripheralModMain;

public class PeripheralModEntryClient implements ClientModInitializer {
    // Since 26.x the chunk section layer (e.g. cutout) of a block quad is derived from the
    // transparency of its sprite (ChunkSectionLayer.byTransparency), so the portal helper block
    // no longer needs a render layer registration.
    
    @Override
    public void onInitializeClient() {
        PeripheralModMain.initClient();
    }
}
