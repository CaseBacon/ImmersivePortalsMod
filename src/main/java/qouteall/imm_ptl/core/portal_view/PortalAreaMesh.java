package qouteall.imm_ptl.core.portal_view;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * View-space triangles of a portal's view area, as drawn by {@link PortalViewRenderer}.
 * <p>
 * When the camera is very close to a portal, parts of the portal can be closer to the camera than the near
 * plane. The rasterizer would clip them away and the world behind the portal would show for a moment.
 * In that case the triangles are projected along the rays through the camera onto a plane just beyond the
 * near plane: they cover exactly the same pixels, but are never near-clipped.
 */
public final class PortalAreaMesh {
    private PortalAreaMesh() {}

    // view-space depth where triangles are cut before the close projection (avoids the camera singularity)
    static final float CLOSE_CUT_DEPTH = 0.001f;

    private final FloatArrayList vertices = new FloatArrayList();

    public static PortalAreaMesh create() {
        return new PortalAreaMesh();
    }

    public void addTriangle(Vector3f a, Vector3f b, Vector3f c) {
        addVertex(a);
        addVertex(b);
        addVertex(c);
    }

    /**
     * Cuts the view-space triangle at {@link #CLOSE_CUT_DEPTH} in front of the camera and projects what is
     * left onto the plane {@code z = -projectionDepth}.
     */
    public void addCloseProjectedTriangle(Vector3f a, Vector3f b, Vector3f c, float projectionDepth) {
        Vector3f[] input = {a, b, c};
        List<Vector3f> polygon = new ArrayList<>(4);
        for (int i = 0; i < 3; i++) {
            Vector3f from = input[i];
            Vector3f to = input[(i + 1) % 3];
            boolean fromIn = -from.z >= CLOSE_CUT_DEPTH;
            boolean toIn = -to.z >= CLOSE_CUT_DEPTH;
            if (fromIn) {
                polygon.add(new Vector3f(from));
            }
            if (fromIn != toIn) {
                float t = (-CLOSE_CUT_DEPTH - from.z) / (to.z - from.z);
                polygon.add(new Vector3f(from).lerp(to, t));
            }
        }
        if (polygon.size() < 3) {
            return;
        }
        for (Vector3f p : polygon) {
            p.mul(projectionDepth / -p.z);
        }
        for (int i = 1; i + 1 < polygon.size(); i++) {
            addTriangle(polygon.get(0), polygon.get(i), polygon.get(i + 1));
        }
    }

    private void addVertex(Vector3f p) {
        vertices.add(p.x);
        vertices.add(p.y);
        vertices.add(p.z);
    }

    public int vertexCount() {
        return vertices.size() / 3;
    }

    public Vector3f getVertex(int index, Vector3f dest) {
        return dest.set(vertices.getFloat(index * 3), vertices.getFloat(index * 3 + 1), vertices.getFloat(index * 3 + 2));
    }

    public FloatArrayList floats() {
        return vertices;
    }
}
