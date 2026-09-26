package qouteall.imm_ptl.core.portal_view;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.mixin.client.portal_view.GameRendererAccessor;
import qouteall.imm_ptl.core.mixin.client.portal_view.LevelExtractorAccessor;
import qouteall.imm_ptl.core.mixin.client.portal_view.LevelRendererAccessor;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Portal rendering prototype for the 26.x renderer (render-to-texture, no stencil).
 * <p>
 * The renderer splits a frame into extraction and drawing, and so does this class:
 * <ul>
 *     <li>{@link #extractViews}: right after the main level extraction, the destination of every visible
 *     portal is extracted with a {@link PortalViewCamera} into a render state that belongs to the view.
 *     The world between the destination camera and the destination plane is clipped with an oblique
 *     near plane ({@link ObliqueClipping}).</li>
 *     <li>{@link #renderViews}: right after the main level is drawn, the destination's {@link LevelRenderer}
 *     draws each view into an offscreen target, then the portal quad is drawn into the main target and
 *     samples the view in screen space. The quad is depth tested and writes depth.</li>
 * </ul>
 * Everything goes through the GPU abstraction, so it runs on OpenGL and Vulkan.
 * <p>
 * Limits of the prototype: one portal layer, at most {@link #MAX_VIEWS} views per frame, only portals
 * into another dimension (a same-dimension view would need a second renderer for the same level),
 * flat portals only.
 */
@Environment(EnvType.CLIENT)
public final class PortalViewRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int MAX_VIEWS = 2;
    // views that were not used for this many frames release their GPU resources
    private static final int VIEW_EXPIRY_FRAMES = 200;

    public static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("immersive_portals", "pipeline/portal_view"))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withVertexShader(Identifier.fromNamespaceAndPath("immersive_portals", "core/portal_view"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("immersive_portals", "core/portal_view"))
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withCull(false)
        .withVertexBinding(0, DefaultVertexFormat.POSITION)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
        .build();

    private static final int QUAD_VERTEX_BYTES = 4 * 3 * Float.BYTES;

    private static final class View implements AutoCloseable {
        final LevelRenderState state = new LevelRenderState();
        final PortalViewCamera camera = new PortalViewCamera();
        final FogRenderer fogRenderer = new FogRenderer();
        final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("immersive_portals portal view");
        final GlobalSettingsUniform globals = new GlobalSettingsUniform();
        final GpuBuffer quadBuffer = RenderSystem.getDevice().createBuffer(
            () -> "immersive_portals portal view quad", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, QUAD_VERTEX_BYTES
        );
        TextureTarget target;
        Portal portal;
        long lastUsedFrame;

        void ensureTarget(int width, int height) {
            if (target == null) {
                target = new TextureTarget(
                    "immersive_portals portal view", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT
                );
            }
            else if (target.width != width || target.height != height) {
                target.resize(width, height);
            }
        }

        @Override
        public void close() {
            fogRenderer.close();
            projectionBuffer.close();
            globals.close();
            quadBuffer.close();
            if (target != null) {
                target.destroyBuffers();
            }
        }
    }

    private static final Map<UUID, View> VIEWS = new HashMap<>();
    private static final List<View> FRAME_VIEWS = new ArrayList<>();
    private static long frameIndex = 0;

    public static void init() {
        RenderPipelines.register(PIPELINE);
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(PortalViewRenderer::cleanup);
    }

    public static boolean isEnabled() {
        return IPGlobal.renderMode == IPGlobal.RenderMode.normal;
    }

    private static void cleanup() {
        FRAME_VIEWS.clear();
        for (View view : VIEWS.values()) {
            view.close();
        }
        VIEWS.clear();
    }

    // ---------------------------------------------------------------- extraction

    /**
     * Called by {@code GameRenderer.extract} after the main level has been extracted.
     */
    public static void extractViews(DeltaTracker deltaTracker) {
        FRAME_VIEWS.clear();
        frameIndex++;
        releaseExpiredViews();

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Entity cameraEntity = mc.getCameraEntity();
        if (!isEnabled() || level == null || mc.player == null || cameraEntity == null
            || !ClientWorldLoader.getIsInitialized()) {
            return;
        }

        GameRenderer gameRenderer = mc.gameRenderer;
        Camera mainCamera = gameRenderer.mainCamera();
        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);

        List<Portal> portals = collectPortalsToRender(level, mainCamera);
        for (Portal portal : portals) {
            ClientLevel destLevel = ClientWorldLoader.getOptionalWorld(portal.getDestDim());
            if (destLevel == null) {
                continue;
            }
            View view = VIEWS.computeIfAbsent(portal.getUUID(), k -> new View());
            view.portal = portal;
            view.lastUsedFrame = frameIndex;

            try {
                extractView(view, portal, mainCamera, level, destLevel, cameraEntity, deltaTracker, partialTicks);
                FRAME_VIEWS.add(view);
            }
            catch (RuntimeException e) {
                LOGGER.error("Failed to extract the view of {}", portal, e);
            }
        }

        if (!FRAME_VIEWS.isEmpty()) {
            // the dispatchers are shared by all level renderers; point them back to the main camera
            mc.levelRenderer.blockEntityRenderDispatcher().prepare(mainCamera.position());
            mc.levelRenderer.entityRenderDispatcher().prepare(mainCamera, mc.crosshairPickEntity);
        }
    }

    private static List<Portal> collectPortalsToRender(ClientLevel level, Camera mainCamera) {
        Vec3 cameraPos = mainCamera.position();
        Frustum frustum = mainCamera.getCullFrustum();
        double range = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;

        List<Portal> result = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Portal portal) {
                result.add(portal);
            }
        }
        result.addAll(GlobalPortalStorage.getGlobalPortals(level));

        result.removeIf(portal -> !portal.isPortalValid()
            || !portal.isVisible()
            || portal.getDestDim() == level.dimension()
            || !portal.isRoughlyVisibleTo(cameraPos)
            || portal.getDistanceToNearestPointInPortal(cameraPos) > range
            || !frustum.isVisible(portal.getThinBoundingBox())
        );
        result.sort(Comparator.comparingDouble(portal -> portal.getDistanceToNearestPointInPortal(cameraPos)));
        return result.size() > MAX_VIEWS ? new ArrayList<>(result.subList(0, MAX_VIEWS)) : result;
    }

    private static void extractView(
        View view, Portal portal, Camera mainCamera, ClientLevel mainLevel, ClientLevel destLevel,
        Entity cameraEntity, DeltaTracker deltaTracker, float partialTicks
    ) {
        Minecraft mc = Minecraft.getInstance();
        view.camera.setupFor(mainCamera, portal, mainLevel, destLevel, cameraEntity, deltaTracker);

        LevelExtractor extractor = ClientWorldLoader.getLevelExtractor(destLevel.dimension());
        LevelExtractorAccessor extractorAccess = (LevelExtractorAccessor) extractor;
        LevelRenderState originalState = extractorAccess.ip_getLevelRenderState();
        extractorAccess.ip_setLevelRenderState(view.state);
        try {
            ClientWorldLoader.withSwitchedWorld(destLevel, () -> extractor.extract(deltaTracker, view.camera, partialTicks));
        }
        finally {
            extractorAccess.ip_setLevelRenderState(originalState);
        }

        // like GameRenderer.extractCamera
        CameraRenderState cameraState = view.state.cameraRenderState;
        view.camera.extractRenderState(cameraState, deltaTracker);
        cameraState.fogType = view.camera.getFluidInCamera();
        cameraState.fogData = view.fogRenderer.setupFog(
            view.camera, mc.options.getEffectiveRenderDistance(), deltaTracker,
            mc.gameRenderer.bossOverlayWorldDarkening(partialTicks), destLevel
        );

        // clip the destination world between the camera and the destination plane
        ObliqueClipping.apply(
            cameraState.projectionMatrix,
            destinationPlaneInViewSpace(portal, cameraState),
            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()
        );
    }

    /**
     * The destination plane of the portal, keeping the content side, in the view space of the camera state.
     */
    static Vector4f destinationPlaneInViewSpace(Portal portal, CameraRenderState cameraState) {
        Vec3 contentDirection = portal.getContentDirection();
        Vec3 toPlane = portal.getDestPos().subtract(cameraState.pos);
        Vector3f normal = cameraState.viewRotationMatrix.transformDirection(new Vector3f(
            (float) contentDirection.x, (float) contentDirection.y, (float) contentDirection.z
        ));
        return new Vector4f(normal.x, normal.y, normal.z, (float) -contentDirection.dot(toPlane));
    }

    private static void releaseExpiredViews() {
        Iterator<View> iterator = VIEWS.values().iterator();
        while (iterator.hasNext()) {
            View view = iterator.next();
            if (frameIndex - view.lastUsedFrame > VIEW_EXPIRY_FRAMES) {
                view.close();
                iterator.remove();
            }
        }
    }

    // ---------------------------------------------------------------- drawing

    /**
     * Called by {@code GameRenderer.renderLevel} after the main level has been drawn.
     */
    public static void renderViews() {
        if (FRAME_VIEWS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        GameRenderer gameRenderer = mc.gameRenderer;
        GameRendererAccessor gameRendererAccess = (GameRendererAccessor) gameRenderer;
        RenderTarget mainTarget = gameRendererAccess.ip_getMainRenderTarget();
        GameRenderState gameRenderState = gameRenderer.gameRenderState();

        GpuBufferSlice savedProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType savedProjectionType = RenderSystem.getProjectionType();
        GpuBufferSlice savedFog = RenderSystem.getShaderFog();
        GpuBuffer savedGlobals = RenderSystem.getGlobalSettingsUniform();

        List<View> drawnViews = new ArrayList<>();
        for (View view : FRAME_VIEWS) {
            try {
                renderView(view, mainTarget, gameRendererAccess, gameRenderState);
                drawnViews.add(view);
            }
            catch (RuntimeException e) {
                LOGGER.error("Failed to render the view of {}", view.portal, e);
            }
            finally {
                gameRendererAccess.ip_setMainRenderTarget(mainTarget);
            }
        }

        if (savedProjection != null) {
            RenderSystem.setProjectionMatrix(savedProjection, savedProjectionType);
        }
        if (savedFog != null) {
            RenderSystem.setShaderFog(savedFog);
        }
        if (savedGlobals != null) {
            RenderSystem.setGlobalSettingsUniform(savedGlobals);
        }

        CameraRenderState mainCamera = gameRenderState.levelRenderState.cameraRenderState;
        for (View view : drawnViews) {
            drawPortalQuad(view, mainTarget, mainCamera);
        }
    }

    private static void renderView(
        View view, RenderTarget mainTarget, GameRendererAccessor gameRendererAccess, GameRenderState gameRenderState
    ) {
        ClientLevel destLevel = ClientWorldLoader.getOptionalWorld(view.portal.getDestDim());
        if (destLevel == null) {
            throw new IllegalStateException("destination world is gone");
        }
        LevelRenderer levelRenderer = ClientWorldLoader.getWorldRenderer(destLevel.dimension());
        view.ensureTarget(mainTarget.width, mainTarget.height);

        CameraRenderState cameraState = view.state.cameraRenderState;
        RenderSystem.setProjectionMatrix(
            view.projectionBuffer.getBuffer(new Matrix4f(cameraState.projectionMatrix)), ProjectionType.PERSPECTIVE
        );
        view.fogRenderer.updateBuffer(cameraState.fogData);
        GpuBufferSlice terrainFog = view.fogRenderer.getBuffer(FogRenderer.FogMode.WORLD);
        view.globals.update(
            mainTarget.width, mainTarget.height,
            gameRenderState.optionsRenderState.glintStrength,
            view.state.gameTime, view.state.worldPartialTicks,
            gameRenderState.optionsRenderState.menuBackgroundBlurriness,
            cameraState.pos,
            gameRenderState.optionsRenderState.textureFiltering == TextureFilteringMethod.RGSS
        );

        LevelRendererAccessor levelRendererAccess = (LevelRendererAccessor) levelRenderer;
        LevelRenderState originalState = levelRendererAccess.ip_getLevelRenderState();
        levelRendererAccess.ip_setLevelRenderState(view.state);
        gameRendererAccess.ip_setMainRenderTarget(view.target);
        try {
            ClientWorldLoader.withSwitchedWorld(destLevel, () -> levelRenderer.render(
                gameRendererAccess.ip_getResourcePool(), false, cameraState,
                terrainFog, cameraState.fogData.color, true, false
            ));
        }
        finally {
            levelRendererAccess.ip_setLevelRenderState(originalState);
            view.fogRenderer.endFrame();
        }
        
        if (debugDumpPath != null) {
            java.nio.file.Path path = debugDumpPath;
            debugDumpPath = null;
            net.minecraft.client.Screenshot.takeScreenshot(view.target, image -> {
                try (image) {
                    image.writeToFile(path);
                    LOGGER.info("Saved portal view to {}", path);
                }
                catch (java.io.IOException e) {
                    LOGGER.error("Failed to save portal view", e);
                }
            });
        }
    }

    private static void drawPortalQuad(View view, RenderTarget mainTarget, CameraRenderState mainCamera) {
        Portal portal = view.portal;
        Vec3 center = portal.getOriginPos().subtract(mainCamera.pos);
        Vec3 halfW = portal.getAxisW().scale(portal.getWidth() / 2);
        Vec3 halfH = portal.getAxisH().scale(portal.getHeight() / 2);
        Vec3[] corners = {
            center.subtract(halfW).subtract(halfH),
            center.add(halfW).subtract(halfH),
            center.add(halfW).add(halfH),
            center.subtract(halfW).add(halfH)
        };

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.malloc(QUAD_VERTEX_BYTES);
            for (Vec3 corner : corners) {
                data.putFloat((float) corner.x).putFloat((float) corner.y).putFloat((float) corner.z);
            }
            data.flip();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(view.quadBuffer.slice(), data);
        }

        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
            .writeTransform(new Matrix4f(mainCamera.viewRotationMatrix));
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(6);

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "immersive_portals portal view",
            mainTarget.getColorTextureView(), Optional.empty(),
            mainTarget.getDepthTextureView(), OptionalDouble.empty()
        )) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setUniform(
                "Sampler0", view.target.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            );
            pass.setVertexBuffer(0, view.quadBuffer.slice());
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(6, 1, 0, 0, 0);
        }
    }

    private static java.nio.file.Path debugDumpPath = null;
    
    /**
     * Debugging: save the offscreen target of the next rendered view as a PNG.
     */
    public static void debugDumpNextView(java.nio.file.Path path) {
        debugDumpPath = path;
    }
    
        /**
     * The number of portal views drawn in the current frame (debug text).
     */
    public static int getViewCountThisFrame() {
        return FRAME_VIEWS.size();
    }
}
