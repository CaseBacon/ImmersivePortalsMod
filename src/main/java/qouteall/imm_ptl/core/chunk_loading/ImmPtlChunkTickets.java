package qouteall.imm_ptl.core.chunk_loading;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongPredicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.ducks.IEServerChunkCache;
import qouteall.imm_ptl.core.ducks.IEWorld;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.RateStat;

import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/**
 * Each {@link ImmPtlChunkTickets} manages ImmPtl chunk ticket for one dimension.
 * <p>
 * It re-implements player chunk loading throttling which is much simpler than vanilla's.
 * In vanilla, each chunk that get loaded by player has a chunk ticket.
 * The chunk tickets are not added immediately, but added by a throttled mechanism.
 * The throttling will reduce the world generation and chunk loading workload when the player moves fast,
 * and prioritize the chunks near player.
 * <p>
 * In vanilla, {@link DistanceManager} throttles player tickets through a ThrottlingChunkTaskDispatcher.
 * <p>
 * Since 1.21.5 tickets live in {@link net.minecraft.world.level.TicketStorage} and a ticket is
 * identified only by its {@link TicketType} and level.
 */
@SuppressWarnings("JavadocReference")
public class ImmPtlChunkTickets {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    /**
     * Loads and simulates (the old region ticket put the chunks at entity-ticking level).
     * Not persisted: the tickets are recomputed from the portals after a restart.
     */
    public static final TicketType TICKET_TYPE = Registry.register(
        BuiltInRegistries.TICKET_TYPE,
        Identifier.fromNamespaceAndPath("immersive_portals", "portal_view"),
        new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION)
    );
    
    // for debugging
    @SuppressWarnings("FieldMayBeFinal")
    private static boolean enableDebugRateStat = false;
    private static final RateStat debugRateStat = new RateStat("imm_ptl_chunk_ticket");
    
    // the fields of ImmPtlChunkTickets should avoid referencing ServerLevel
    public static final WeakHashMap<ServerLevel, ImmPtlChunkTickets> BY_DIMENSION = new WeakHashMap<>();
    
    public static void init() {
        DimensionAPI.SERVER_PRE_REMOVE_DIMENSION_EVENT.register(
            ImmPtlChunkTickets::onDimensionRemove
        );
        
        IPGlobal.SERVER_CLEANUP_EVENT.register(ImmPtlChunkTickets::cleanup);
    }
    
    public static class ChunkTicketInfo {
        public int lastUpdateGeneration;
        public int distanceToSource;
        
        public ChunkTicketInfo(int lastUpdateGeneration, int distanceToSource) {
            this.lastUpdateGeneration = lastUpdateGeneration;
            this.distanceToSource = distanceToSource;
        }
    }
    
    private final Long2ObjectOpenHashMap<ChunkTicketInfo> chunkPosToTicketInfo = new Long2ObjectOpenHashMap<>();
    
    private final ArrayList<LongLinkedOpenHashSet> chunksToAddTicketByDistance = new ArrayList<>();
    
    /**
     * chunk pos -> game time when its ticket was added
     */
    private final Long2LongOpenHashMap waitingForLoading = new Long2LongOpenHashMap();
    
    /**
     * A chunk that has not become entity-ticking after this many ticks no longer occupies a throttling slot.
     */
    private static final long LOADING_WAIT_LIMIT_TICKS = 20 * 30;
    
    private boolean isValid = true;
    
    public final int throttlingLimit = 4;
    
    private ImmPtlChunkTickets() {
    
    }
    
    // it takes in world instead of dimension id, to ensure dimension really exists
    public static ImmPtlChunkTickets get(ServerLevel world) {
        return BY_DIMENSION.computeIfAbsent(world, k -> new ImmPtlChunkTickets());
    }
    
    public void markForLoading(long chunkPos, int distanceToSource, int generation) {
        Validate.isTrue(distanceToSource >= 0);
        
        ChunkTicketInfo info = chunkPosToTicketInfo.get(chunkPos);
        
        if (info == null) {
            info = new ChunkTicketInfo(generation, distanceToSource);
            chunkPosToTicketInfo.put(chunkPos, info);
            getQueueByDistance(distanceToSource).add(chunkPos);
        }
        else {
            if (generation != info.lastUpdateGeneration) {
                info.lastUpdateGeneration = generation;
                int oldDistanceToSource = info.distanceToSource;
                info.distanceToSource = distanceToSource;
                if (getQueueByDistance(oldDistanceToSource).remove(chunkPos)) {
                    getQueueByDistance(distanceToSource).add(chunkPos);
                }
            }
            else {
                if (distanceToSource < info.distanceToSource) {
                    int oldDistanceToSource = info.distanceToSource;
                    info.distanceToSource = distanceToSource;
                    if (getQueueByDistance(oldDistanceToSource).remove(chunkPos)) {
                        getQueueByDistance(distanceToSource).add(chunkPos);
                    }
                }
            }
        }
    }
    
    private LongLinkedOpenHashSet getQueueByDistance(int distanceToSource) {
        return Helper.arrayListComputeIfAbsent(
            chunksToAddTicketByDistance,
            distanceToSource,
            LongLinkedOpenHashSet::new
        );
    }
    
    public void tick(ServerLevel world) {
        flushThrottling(world);
    }
    
    /**
     * This method is called during ticking and {@link DistanceManager#runAllUpdates(ChunkMap)} .
     * <p>
     * Only calling this method during ticking will make it throttled too slow.
     * <p>
     * This method uses the chunk holder's future, so it should be called after
     * {@link DistanceManager#runAllUpdates(ChunkMap)}
     * (as it calls {@link ChunkHolder#updateFutures(ChunkMap, Executor)}).
     * Before updating the future, the chunk's entity ticking future may be a future that immediately returns {@link ChunkHolder.ChunkLoadingFailure} result.
     * Each task to {@link net.minecraft.server.level.ServerChunkCache.MainThreadExecutor} will trigger
     * {@link DistanceManager#runAllUpdates(ChunkMap)}.
     */
    public void flushThrottling(ServerLevel world) {
        if (Thread.currentThread() != ((IEWorld) world).portal_getThread()) {
            LOGGER.error("Called in a non-server-main (or server-world) thread.", new Throwable());
            return;
        }
        
        if (enableDebugRateStat) {
            debugRateStat.update();
        }
        
        if (!isValid) {
            LOGGER.error("flushing when invalid {}", world);
            return;
        }
        
        if (!world.getServer().isRunning()) {
            // important: don't add chunk ticket when server is saving
            // https://github.com/iPortalTeam/ImmersivePortalsMod/issues/1455
            return;
        }
        
        // clear the already loaded chunks
        long gameTime = world.getGameTime();
        waitingForLoading.long2LongEntrySet().removeIf(entry -> {
            long chunkPos = entry.getLongKey();
            ChunkHolder chunkHolder = getChunkHolder(world, chunkPos);
            if (chunkHolder == null) {
                // the ticket was added but the chunk map has not processed it yet
                return isWaitingTooLong(world, chunkPos, entry.getLongValue(), gameTime);
            }
            
            ChunkResult<LevelChunk> resultNow = chunkHolder.getEntityTickingChunkFuture()
                .getNow(null);
            
            if (resultNow == null) {
                return false;
            }
            
            if (!resultNow.isSuccess()) {
                if (!ChunkLevel.isEntityTicking(chunkHolder.getTicketLevel())) {
                    // The holder has not been raised to the ticket's level yet
                    // (tickets are applied in DistanceManager.runAllUpdates),
                    // so its entity ticking future is still the completed "unloaded" placeholder.
                    return isWaitingTooLong(world, chunkPos, entry.getLongValue(), gameTime);
                }
                
                LOGGER.error(
                    "Chunk loading failure {} {} {}",
                    world, ChunkPos.unpack(chunkPos), resultNow.getError()
                );
            }
            
            return true;
        });
        
        // flush the pending-add-ticket queues
        for (LongLinkedOpenHashSet queue : chunksToAddTicketByDistance) {
            if (queue != null) {
                while (!queue.isEmpty()) {
                    if (waitingForLoading.size() >= throttlingLimit) {
                        return;
                    }
                    
                    long chunkPos = queue.removeFirstLong();
                    if (chunkPosToTicketInfo.containsKey(chunkPos)) {
                        if (addTicket(world, chunkPos)) {
                            waitingForLoading.put(chunkPos, gameTime);
                        }
                    }
                    else {
                        LOGGER.warn("Chunk {} is not in the queue", ChunkPos.unpack(chunkPos));
                    }
                }
            }
        }
    }
    
    private static boolean isWaitingTooLong(
        ServerLevel world, long chunkPos, long ticketGameTime, long gameTime
    ) {
        if (gameTime - ticketGameTime > LOADING_WAIT_LIMIT_TICKS) {
            LOGGER.warn(
                "Chunk {} {} did not become entity-ticking within {} ticks",
                world, ChunkPos.unpack(chunkPos), LOADING_WAIT_LIMIT_TICKS
            );
            return true;
        }
        return false;
    }
    
    /**
     * @return whether a ticket was added
     */
    private static boolean addTicket(ServerLevel world, long chunkPos) {
        if (!IPConfig.getConfig().enableImmPtlChunkLoading) {
            return false;
        }
        
        ChunkPos chunkPosObj = ChunkPos.unpack(chunkPos);
        world.getChunkSource().addTicketWithRadius(TICKET_TYPE, chunkPosObj, getLoadingRadius());
        
        if (enableDebugRateStat) {
            debugRateStat.hit();
        }
        
        return true;
    }
    
    public void purge(
        ServerLevel world,
        LongPredicate shouldKeepLoadingFunc
    ) {
        chunkPosToTicketInfo.long2ObjectEntrySet().removeIf(e -> {
            long chunkPos = e.getLongKey();
            ChunkTicketInfo ticketInfo = e.getValue();
            
            boolean keepLoading = shouldKeepLoadingFunc.test(chunkPos);
            
            if (!keepLoading) {
                waitingForLoading.remove(chunkPos);
                
                boolean pendingTicketAdding = getQueueByDistance(ticketInfo.distanceToSource)
                    .remove(chunkPos);
                
                if (!pendingTicketAdding) {
                    ChunkPos chunkPosObj = ChunkPos.unpack(chunkPos);
                    world.getChunkSource().removeTicketWithRadius(
                        TICKET_TYPE, chunkPosObj, getLoadingRadius()
                    );
                }
                return true;
            }
            else {
                return false;
            }
        });
    }
    
    public int getLoadedChunkNum() {
        return chunkPosToTicketInfo.size();
    }
    
    public static void onDimensionRemove(ServerLevel world) {
        ImmPtlChunkTickets dimTicketManager = BY_DIMENSION.remove(world);
        
        if (dimTicketManager == null) {
            return;
        }
        
        removeAllTicketsInWorld(world, dimTicketManager);
    }
    
    private static void removeAllTicketsInWorld(ServerLevel world, ImmPtlChunkTickets dimTicketManager) {
        dimTicketManager.chunkPosToTicketInfo.keySet().forEach((long pos) -> {
            ChunkPos chunkPos = ChunkPos.unpack(pos);
            // the loading radius is a config option that may have changed
            // tickets are identified by type and level, so remove every possible radius
            world.getChunkSource().removeTicketWithRadius(TICKET_TYPE, chunkPos, 1);
            world.getChunkSource().removeTicketWithRadius(TICKET_TYPE, chunkPos, 2);
        });
        
        dimTicketManager.isValid = false;
    }
    
    public static int getLoadingRadius() {
        if (IPGlobal.activeLoading) {
            return 2;
        }
        else {
            return 1;
        }
    }
    
    public static ChunkHolder getChunkHolder(ServerLevel world, long chunkPos) {
        return ((IEChunkMap) (world.getChunkSource()).chunkMap).ip_getChunkHolder(chunkPos);
    }
    
    public static DistanceManager getDistanceManager(ServerLevel world) {
        return ((IEServerChunkCache) world.getChunkSource()).ip_getDistanceManager();
    }
    
    private static void cleanup(MinecraftServer server) {
        for (ImmPtlChunkTickets immPtlChunkTickets : BY_DIMENSION.values()) {
            immPtlChunkTickets.isValid = false;
        }
        BY_DIMENSION.clear();
    }
}
