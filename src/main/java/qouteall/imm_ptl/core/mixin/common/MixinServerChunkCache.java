package qouteall.imm_ptl.core.mixin.common;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.ducks.IEServerChunkCache;

@Mixin(ServerChunkCache.class)
public abstract class MixinServerChunkCache implements IEServerChunkCache {
    @Shadow
    @Final
    private DistanceManager distanceManager;
    
    @Shadow
    @Final
    private TicketStorage ticketStorage;
    
    @Override
    public DistanceManager ip_getDistanceManager() {
        return distanceManager;
    }
    
    @Override
    public TicketStorage ip_getTicketStorage() {
        return ticketStorage;
    }
}
