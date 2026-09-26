package qouteall.imm_ptl.core.mc_utils;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.phys.Vec3;

/**
 * Replaces the {@code MultiBufferSource} that wire rendering used to draw into
 * ({@code RenderType.lines()} and {@code RenderType.debugLineStrip(1)}).
 * The lines are emitted as vanilla gizmos, see {@link GizmoLineVertexConsumer}.
 */
@Environment(EnvType.CLIENT)
public final class WireBuffers {
    private final GizmoLineVertexConsumer lines;
    private final GizmoLineVertexConsumer lineStrip;

    public WireBuffers(Vec3 cameraPos) {
        this.lines = new GizmoLineVertexConsumer(GizmoLineVertexConsumer.Mode.LINES, cameraPos, 2.0f);
        this.lineStrip = new GizmoLineVertexConsumer(GizmoLineVertexConsumer.Mode.LINE_STRIP, cameraPos, 1.0f);
    }

    public GizmoLineVertexConsumer lines() {
        return lines;
    }

    public GizmoLineVertexConsumer lineStrip() {
        return lineStrip;
    }

    public void flush() {
        lines.flush();
        lineStrip.flush();
    }
}
