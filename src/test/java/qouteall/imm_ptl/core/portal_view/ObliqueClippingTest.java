package qouteall.imm_ptl.core.portal_view;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ObliqueClippingTest {
    private static final float NEAR = 0.05f;
    private static final float FAR = 1024f;

    private static Matrix4f projection(boolean reversed, boolean zeroToOne) {
        float fov = (float) Math.toRadians(70);
        // vanilla (net.minecraft.client.renderer.Projection) gets reversed depth by swapping near and far
        return reversed
            ? new Matrix4f().setPerspective(fov, 16f / 9f, FAR, NEAR, zeroToOne)
            : new Matrix4f().setPerspective(fov, 16f / 9f, NEAR, FAR, zeroToOne);
    }

    private static Vector4f plane(Vector3f normal, Vector3f point) {
        normal.normalize();
        return new Vector4f(normal.x, normal.y, normal.z, -normal.dot(point));
    }

    private static float ndcZ(Matrix4f m, Vector3f p) {
        Vector4f v = m.transform(new Vector4f(p, 1));
        return v.z / v.w;
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    public void nearPlaneBecomesTheClipPlane(boolean reversed, boolean zeroToOne) {
        Matrix4f m = projection(reversed, zeroToOne);
        assertEquals(reversed, ObliqueClipping.isReversedDepth(m));

        float nearNdc = reversed ? 1 : (zeroToOne ? 0 : -1);
        float farNdc = reversed ? (zeroToOne ? 0 : -1) : 1;

        // a tilted plane 5 blocks in front of the camera; the far side is kept
        Vector3f onPlane = new Vector3f(0, 0, -5);
        Vector3f normal = new Vector3f(0.3f, -0.2f, -1);
        Vector4f plane = plane(new Vector3f(normal), onPlane);
        ObliqueClipping.apply(m, plane, zeroToOne);

        // points on the plane are exactly on the new near plane
        Vector3f tangent = new Vector3f(normal).normalize().cross(0, 1, 0).normalize();
        for (Vector3f p : new Vector3f[]{
            new Vector3f(onPlane), new Vector3f(onPlane).add(new Vector3f(tangent).mul(2)),
            new Vector3f(onPlane).sub(new Vector3f(tangent).mul(1.5f))
        }) {
            assertEquals(nearNdc, ndcZ(m, p), 1e-3, "point on the plane " + p);
        }

        // points beyond the plane are inside the depth range
        for (float z : new float[]{-6, -20, -100}) {
            float d = ndcZ(m, new Vector3f(0, 0, z));
            assertTrue(isBetween(d, nearNdc, farNdc), "kept point at z=" + z + " has depth " + d);
        }

        // points between the camera and the plane are outside the depth range (clipped)
        for (float z : new float[]{-1, -3, -4.5f}) {
            float d = ndcZ(m, new Vector3f(0, 0, z));
            assertTrue(!isBetween(d, nearNdc, farNdc), "clipped point at z=" + z + " has depth " + d);
        }
    }

    private static boolean isBetween(float value, float a, float b) {
        return value >= Math.min(a, b) && value <= Math.max(a, b);
    }
}
