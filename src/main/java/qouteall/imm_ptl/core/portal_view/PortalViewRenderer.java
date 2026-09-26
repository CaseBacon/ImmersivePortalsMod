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
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
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
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap;
import qouteall.imm_ptl.core.mixin.client.portal_view.GameRendererAccessor;
import qouteall.imm_ptl.core.mixin.client.portal_view.LevelExtractorAccessor;
import qouteall.imm_ptl.core.mixin.client.portal_view.LevelRendererAccessor;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
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
 * Limits of the prototype: one portal layer, at most {@link #MAX_VIEWS} views per frame, no mirrors.
 * A same-dimension view reuses the level's renderer with a second camera in the same frame.
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
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
        .build();

    private static final int VERTEX_BYTES = 3 * Float.BYTES;

    /**
     * Within this distance of the portal, the portal area is drawn projected onto a plane just beyond the near
     * plane ({@link PortalAreaMesh#addCloseProjectedTriangle}). Occlusion by things between the camera and the
     * portal is ignored then, which is fine at this distance.
     */
    private static final double CLOSE_DISTANCE = 0.3;
    private static final float CLOSE_PROJECTION_DEPTH = Camera.PROJECTION_Z_NEAR * 1.2f;

    /**
     * The destination plane is kept at least this far away from the destination camera, so that the oblique
     * near plane never passes through the camera.
     */
    private static final float MIN_CLIP_PLANE_DISTANCE = 0.005f;

    private static final class View implements AutoCloseable {
        final LevelRenderState state = new LevelRenderState();
        final PortalViewCamera camera = new PortalViewCamera();
        final FogRenderer fogRenderer = new FogRenderer();
        final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("immersive_portals portal view");
        final GlobalSettingsUniform globals = new GlobalSettingsUniform();
        GpuBuffer areaBuffer;
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

        GpuBuffer ensureAreaBuffer(int bytes) {
            if (areaBuffer == null || areaBuffer.size() < bytes) {
                if (areaBuffer != null) {
                    areaBuffer.close();
                }
                areaBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "immersive_portals portal view area", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    Math.max(bytes, 6 * VERTEX_BYTES)
                );
            }
            return areaBuffer;
        }

        @Override
        public void close() {
            fogRenderer.close();
            projectionBuffer.close();
            globals.close();
            if (areaBuffer != null) {
                areaBuffer.close();
            }
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
            // a reflection cannot be expressed by a camera rotation (and it flips the face culling)
            || portal instanceof Mirror
            || !portal.isVisible()
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
            if (destLevel == mainLevel) {
                // same dimension: the main extractor already consumed this frame's chunk deltas
                // for the main render state, which has not been drawn yet
                ImmPtlClientChunkMap.withoutTrackingSetConsumption(
                    () -> extractor.extract(deltaTracker, view.camera, partialTicks)
                );
            }
            else {
                ClientWorldLoader.withSwitchedWorld(destLevel, () -> extractor.extract(deltaTracker, view.camera, partialTicks));
            }
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
        // the camera is on the clipped side (plane value < 0); keep it a small distance away from the plane
        float d = Math.min((float) -contentDirection.dot(toPlane), -MIN_CLIP_PLANE_DISTANCE);
        return new Vector4f(normal.x, normal.y, normal.z, d);
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
            drawPortalArea(view, mainTarget, mainCamera);
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

        // The clouds' uniform buffer is written by every LevelRenderer.render call and rotated once per frame
        // (LevelRenderer.endFrame, which vanilla only calls for the player's level renderer). A draw recorded
        // earlier in this frame by the same level renderer must keep its data, so this draw gets the next
        // (fenced) buffer.
        levelRenderer.endFrame();

        LevelRendererAccessor levelRendererAccess = (LevelRendererAccessor) levelRenderer;
        LevelRenderState originalState = levelRendererAccess.ip_getLevelRenderState();
        levelRendererAccess.ip_setLevelRenderState(view.state);
        gameRendererAccess.ip_setMainRenderTarget(view.target);
        Runnable render = () -> levelRenderer.render(
            gameRendererAccess.ip_getResourcePool(), false, cameraState,
            terrainFog, cameraState.fogData.color, true, false
        );
        try {
            if (destLevel == Minecraft.getInstance().level) {
                render.run();
            }
            else {
                ClientWorldLoader.withSwitchedWorld(destLevel, render);
            }
        }
        finally {
            levelRendererAccess.ip_setLevelRenderState(originalState);
            view.fogRenderer.endFrame();
        }
        
        if (debugDumpPath != null) {
            Path path = debugDumpPath;
            debugDumpPath = null;
            Screenshot.takeScreenshot(view.target, image -> {
                try (image) {
                    image.writeToFile(path);
                    LOGGER.info("Saved portal view to {}", path);
                }
                catch (IOException e) {
                    LOGGER.error("Failed to save portal view", e);
                }
            });
        }
    }

    private static void drawPortalArea(View view, RenderTarget mainTarget, CameraRenderState mainCamera) {
        Portal portal = view.portal;
        Matrix4f viewRotation = mainCamera.viewRotationMatrix;
        boolean close = portal.getDistanceToNearestPointInPortal(mainCamera.pos) < CLOSE_DISTANCE;

        // the portal's view area (the shape of the portal) as triangles in view space
        PortalAreaMesh mesh = PortalAreaMesh.create();
        portal.renderViewAreaMesh(
            portal.getOriginPos().subtract(mainCamera.pos),
            (x0, y0, z0, x1, y1, z1, x2, y2, z2) -> {
                Vector3f p0 = viewRotation.transformPosition((float) x0, (float) y0, (float) z0, new Vector3f());
                Vector3f p1 = viewRotation.transformPosition((float) x1, (float) y1, (float) z1, new Vector3f());
                Vector3f p2 = viewRotation.transformPosition((float) x2, (float) y2, (float) z2, new Vector3f());
                if (close) {
                    mesh.addCloseProjectedTriangle(p0, p1, p2, CLOSE_PROJECTION_DEPTH);
                }
                else {
                    mesh.addTriangle(p0, p1, p2);
                }
            }
        );
        int vertexCount = mesh.vertexCount();
        FloatArrayList vertices = mesh.floats();
        if (vertexCount == 0) {
            return;
        }

        int bytes = vertexCount * VERTEX_BYTES;
        GpuBuffer areaBuffer = view.ensureAreaBuffer(bytes);
        ByteBuffer data = MemoryUtil.memAlloc(bytes);
        try {
            for (int i = 0; i < vertices.size(); i++) {
                data.putFloat(vertices.getFloat(i));
            }
            data.flip();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(areaBuffer.slice(0, bytes), data);
        }
        finally {
            MemoryUtil.memFree(data);
        }

        // the vertices are already in view space
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f());

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
            pass.setVertexBuffer(0, areaBuffer.slice(0, bytes));
            pass.draw(vertexCount, 1, 0, 0);
        }
    }

    private static @Nullable Path debugDumpPath = null;

    /**
     * Debugging: save the offscreen target of the next rendered view as a PNG.
     */
    public static void debugDumpNextView(Path path) {
        debugDumpPath = path;
    }

    /**
     * The number of portal views drawn in the current frame (debug text).
     */
    public static int getViewCountThisFrame() {
        return FRAME_VIEWS.size();
    }
}
