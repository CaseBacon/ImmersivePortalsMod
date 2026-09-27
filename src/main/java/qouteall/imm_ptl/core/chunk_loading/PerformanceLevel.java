package qouteall.imm_ptl.core.chunk_loading;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;

public enum PerformanceLevel {
    good, medium, bad;
    
    /**
     * The level a client reports to the server, which caps how far the server loads chunks through portals for it
     * ({@link #getIndirectLoadingRadiusCap}, {@link #getVisiblePortalRangeChunks}).
     * <p>
     * PORT(26.3): this used to depend on the frame rate as well (good above 50 FPS, bad at 30 FPS or below), so the
     * chunk loading radius through a portal dropped to 2 chunks at typical shader pack frame rates and with a
     * 30 FPS limit. Chunks loaded through a portal cost the client memory (chunk data and meshes, built on worker
     * threads); they cost no frame time unless they are visible, and then they are what the player expects to see.
     * So only the free memory counts.
     */
    public static PerformanceLevel getClientPerformanceLevel(int averageFreeMemoryMB) {
        if (averageFreeMemoryMB > 800) {
            return good;
        }
        else if (averageFreeMemoryMB > 300) {
            return medium;
        }
        else {
            return bad;
        }
    }
    
    
    public static PerformanceLevel getServerPerformanceLevel(MinecraftServer server) {
        ServerTickRateManager tickRateManager = server.tickRateManager();
        long averageTickTimeNanos = server.getAverageTickTimeNanos();
        
        long nanosecondsPerTick = tickRateManager.nanosecondsPerTick();
        
        if (averageTickTimeNanos < nanosecondsPerTick * 0.8) {
            return good;
        }
        else if (averageTickTimeNanos < nanosecondsPerTick) {
            return medium;
        }
        else {
            return bad;
        }
    }
    
    public static int getVisiblePortalRangeChunks(PerformanceLevel level) {
        if (level == good) {
            return 8;
        }
        else if (level == medium) {
            return 3;
        }
        else {
            return 1;
        }
    }
    
    public static int getIndirectVisiblePortalRangeChunks(PerformanceLevel level) {
        if (level == good) {
            return 2;
        }
        else if (level == medium) {
            return 1;
        }
        else {
            return 0;
        }
    }
    
    public static int getIndirectLoadingRadiusCap(
        PerformanceLevel level
    ) {
        if (level == good) {
            return 32;
        }
        else if (level == medium) {
            return 7;
        }
        else {
            return 2;
        }
    }
    
    public static int getPortalRenderingDistance(
        PerformanceLevel level, int originalDistance
    ) {
        if (level == good) {
            return originalDistance;
        }
        else if (level == medium) {
            return Math.max(2, originalDistance / 2);
        }
        else {
            return 2;
        }
    }
}
