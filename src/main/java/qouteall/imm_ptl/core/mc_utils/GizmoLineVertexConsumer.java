package qouteall.imm_ptl.core.mc_utils;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;

/**
 * Turns line vertices into vanilla line gizmos.
 * <p>
 * Since 26.x the world is drawn from extracted render state, and immediate-mode
 * {@code MultiBufferSource} line rendering from the debug renderer no longer exists.
 * The wire rendering code builds camera-relative line vertices; this consumer converts
 * them back to world positions and emits them through {@link Gizmos}, which vanilla
 * collects during level extraction and draws on both the OpenGL and the Vulkan backend.
 * <p>
 * Must only be used while a gizmo collector is active (e.g. inside {@code DebugRenderer.emitGizmos}).
 */
@Environment(EnvType.CLIENT)
public final class GizmoLineVertexConsumer implements VertexConsumer {
    public enum Mode {
        /**
         * Every two vertices form one line (like the vanilla "lines" render type).
         */
        LINES,
        /**
         * Consecutive vertices are connected. Segments touching a vertex with alpha 0 are skipped,
         * which is how the wire rendering code "jumps" inside a strip.
         */
        LINE_STRIP
    }

    private final Mode mode;
    private final Vec3 cameraPos;
    private final float lineWidth;

    private boolean hasCurrent = false;
    private float currentX, currentY, currentZ;
    private int currentColor;

    private boolean hasPrevious = false;
    private float previousX, previousY, previousZ;
    private int previousColor;

    public GizmoLineVertexConsumer(Mode mode, Vec3 cameraPos, float lineWidth) {
        this.mode = mode;
        this.cameraPos = cameraPos;
        this.lineWidth = lineWidth;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        finishVertex();
        hasCurrent = true;
        currentX = x;
        currentY = y;
        currentZ = z;
        currentColor = 0xFFFFFFFF;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        currentColor = ARGB.color(a, r, g, b);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        currentColor = color;
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }

    /**
     * Emits the pending vertex. Call after the last vertex.
     */
    public void flush() {
        finishVertex();
        hasPrevious = false;
    }

    private void finishVertex() {
        if (!hasCurrent) {
            return;
        }
        hasCurrent = false;

        if (hasPrevious) {
            switch (mode) {
                case LINES -> {
                    // the line takes the color of its first vertex
                    if (ARGB.alpha(previousColor) != 0) {
                        emit(previousColor);
                    }
                    hasPrevious = false;
                    return;
                }
                case LINE_STRIP -> {
                    if (ARGB.alpha(previousColor) != 0 && ARGB.alpha(currentColor) != 0) {
                        emit(currentColor);
                    }
                }
            }
        }

        hasPrevious = true;
        previousX = currentX;
        previousY = currentY;
        previousZ = currentZ;
        previousColor = currentColor;
    }

    private void emit(int color) {
        Gizmos.line(
            new Vec3(previousX + cameraPos.x, previousY + cameraPos.y, previousZ + cameraPos.z),
            new Vec3(currentX + cameraPos.x, currentY + cameraPos.y, currentZ + cameraPos.z),
            color, lineWidth
        );
    }
}
