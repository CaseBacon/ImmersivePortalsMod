package qouteall.imm_ptl.core.portal;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.q_misc_util.Helper;

import java.lang.ref.Cleaner;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// A portal's rendering related things
// to access the package private field of Portal, this class is not in "render" package
@SuppressWarnings("resource")
@Environment(EnvType.CLIENT)
public class PortalRenderInfo implements AutoCloseable {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // 26.3 port: the OpenGL occlusion queries (GlQueryObject) were removed together with the
    // raw-GL renderer. Visibility prediction will be rebuilt on renderpearl GpuQueryPool
    // (or CPU visibility) in the renderer phase; see docs/26.3-port-progress.md.
    public static class Visibility {
        public Boolean lastFrameRendered;
        public Boolean thisFrameRendered;
        
        public Visibility() {
            lastFrameRendered = null;
        }
        
        void update() {
            lastFrameRendered = thisFrameRendered;
            thisFrameRendered = null;
        }
        
        void dispose() {
        }
    }
    
    private final Map<List<UUID>, Visibility> infoMap = new HashMap<>();
    
    public int thisFrameQueryFrameIndex = -1;
    
    private long mispredictTime1 = 0;
    private long mispredictTime2 = 0;
    
    private int totalMispredictCount = 0;
    
    public static void init() {
        Portal.CLIENT_PORTAL_TICK_SIGNAL.register(portal -> {
            PortalRenderInfo presentation = getOptional(portal);
            if (presentation != null) {
                presentation.tick(portal);
            }
        });
        
        Portal.PORTAL_DISPOSE_SIGNAL.register(portal -> {
            if (portal.level().isClientSide()) {
                PortalRenderInfo renderInfo = getOptional(portal);
                if (renderInfo != null) {
                    renderInfo.dispose();
                }
            }
        });
    }
    
    @Nullable
    public static PortalRenderInfo getOptional(Portal portal) {
        Validate.isTrue(portal.level().isClientSide());
        
        return portal.portalRenderInfo;
    }
    
    public static PortalRenderInfo get(Portal portal) {
        Validate.isTrue(portal.level().isClientSide());
        
        if (portal.portalRenderInfo == null) {
            portal.portalRenderInfo = new PortalRenderInfo();
        }
        return portal.portalRenderInfo;
    }
    
    public PortalRenderInfo() {
        CLEANER.register(this, getGcDirectedCleaningFunc());
    }
    
    private void tick(Portal portal) {
        Validate.isTrue(portal.level().isClientSide());
    }
    
    // disposing twice is fine
    public void dispose() {
        disposeInfoMap(infoMap);
    }
    
    private Runnable getGcDirectedCleaningFunc() {
        Map<List<UUID>, Visibility> infoMap1 = this.infoMap;
        // the disposal func should not reference this
        return () -> {
            LOGGER.debug("Running GC-directed PortalRenderInfo clean");
            
            // the cleaner runs on its own thread. Use the task list to avoid thread safety issue.
            IPGlobal.PRE_TOTAL_RENDER_TASK_LIST.addOneShotTask(() -> {
                disposeInfoMap(infoMap1);
            });
        };
    }
    
    @Override
    public void close() throws Exception {
        dispose();
    }
    
    // running it twice is fine
    private static void disposeInfoMap(Map<List<UUID>, Visibility> infoMap) {
        infoMap.values().forEach(Visibility::dispose);
        infoMap.clear();
    }
    
    // Visibility Predicting -----
    
    private void updateQuerySet() {
        if (RenderStates.frameIndex != thisFrameQueryFrameIndex) {
            
            if (RenderStates.frameIndex == thisFrameQueryFrameIndex + 1) {
                infoMap.entrySet().removeIf(entry -> {
                    Visibility visibility = entry.getValue();
                    
                    return visibility.lastFrameRendered == null &&
                        visibility.thisFrameRendered == null;
                });
                
                infoMap.values().forEach(Visibility::update);
            }
            else {
                disposeInfoMap(infoMap);
            }
            
            thisFrameQueryFrameIndex = RenderStates.frameIndex;
        }
    }
    
    @NotNull
    private Visibility getVisibility(List<UUID> desc) {
        updateQuerySet();
        
        return infoMap.computeIfAbsent(desc, k -> new Visibility());
    }
    
    private void onMispredict() {
        mispredictTime1 = mispredictTime2;
        mispredictTime2 = System.nanoTime();
        totalMispredictCount++;
    }
    
    private boolean isFrequentlyMispredicted() {
        if (totalMispredictCount > 5) {
            return true;
        }
        
        long currTime = System.nanoTime();
        
        return (currTime - mispredictTime1) < Helper.secondToNano(30);
    }
    
    private void updatePredictionStatus(Visibility visibility, boolean thisFrameDecision) {
        visibility.thisFrameRendered = thisFrameDecision;
        
        if (thisFrameDecision) {
            if (visibility.lastFrameRendered != null) {
                if (!visibility.lastFrameRendered) {
                    if (!isFrequentlyMispredicted()) {
                        onMispredict();
                    }
                }
            }
        }
    }
    
    /**
     * Without occlusion queries every portal that passes frustum culling is treated as visible.
     */
    public static boolean renderAndDecideVisibility(Portal portal, Runnable queryRendering) {
        return true;
    }
    
    private static final Cleaner CLEANER = Cleaner.create();
}
