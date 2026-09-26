package qouteall.imm_ptl.core.ducks;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.world.level.TicketStorage;

public interface IEServerChunkCache {
    DistanceManager ip_getDistanceManager();
    
    TicketStorage ip_getTicketStorage();
}
