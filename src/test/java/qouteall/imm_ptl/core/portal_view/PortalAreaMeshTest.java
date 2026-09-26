package qouteall.imm_ptl.core.portal_view;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PortalAreaMeshTest {
    private static final float DEPTH = 0.06f;

    @Test
    public void projectionKeepsTheScreenPosition() {
        Vector3f a = new Vector3f(-1, -1, -0.2f);
        Vector3f b = new Vector3f(1, -1, -0.02f);
        Vector3f c = new Vector3f(0, 1, -0.5f);
        PortalAreaMesh mesh = PortalAreaMesh.create();
        mesh.addCloseProjectedTriangle(a, b, c, DEPTH);

        assertEquals(3, mesh.vertexCount());
        Vector3f[] expected = {a, b, c};
        for (int i = 0; i < 3; i++) {
            Vector3f v = mesh.getVertex(i, new Vector3f());
            assertEquals(-DEPTH, v.z, 1e-6);
            // same ray through the camera: same x/z and y/z
            assertEquals(expected[i].x / expected[i].z, v.x / v.z, 1e-4);
            assertEquals(expected[i].y / expected[i].z, v.y / v.z, 1e-4);
        }
    }

    @Test
    public void partBehindTheCameraIsCut() {
        // one vertex behind the camera (z > 0): the visible part becomes a quad (2 triangles)
        PortalAreaMesh mesh = PortalAreaMesh.create();
        mesh.addCloseProjectedTriangle(
            new Vector3f(-1, 0, -1), new Vector3f(1, 0, -1), new Vector3f(0, 0, 1), DEPTH
        );

        assertEquals(6, mesh.vertexCount());
        for (int i = 0; i < mesh.vertexCount(); i++) {
            Vector3f v = mesh.getVertex(i, new Vector3f());
            assertEquals(-DEPTH, v.z, 1e-6);
            assertTrue(Float.isFinite(v.x) && Float.isFinite(v.y));
        }
    }

    @Test
    public void triangleBehindTheCameraIsDropped() {
        PortalAreaMesh mesh = PortalAreaMesh.create();
        mesh.addCloseProjectedTriangle(
            new Vector3f(-1, 0, 1), new Vector3f(1, 0, 1), new Vector3f(0, 1, 0.5f), DEPTH
        );
        assertEquals(0, mesh.vertexCount());
    }

    @Test
    public void plainTrianglesAreKept() {
        PortalAreaMesh mesh = PortalAreaMesh.create();
        mesh.addTriangle(new Vector3f(1, 2, 3), new Vector3f(4, 5, 6), new Vector3f(7, 8, 9));
        assertEquals(3, mesh.vertexCount());
        assertEquals(new Vector3f(4, 5, 6), mesh.getVertex(1, new Vector3f()));
    }
}
