package qouteall.imm_ptl.core.portal_view;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Oblique near-plane clipping (E. Lengyel, "Oblique View Frustum Depth Projection and Clipping").
 * <p>
 * The near plane of a perspective projection is replaced with an arbitrary view-space plane, so that
 * everything on the negative side of the plane is clipped by the rasterizer. This clips the world behind
 * a portal's destination without stencil and without changing any shader, so it works on every backend.
 * <p>
 * Works for regular and reversed depth, and for both clip-space depth ranges ([0, 1] and [-1, 1]).
 * The depth convention is derived from the matrix itself.
 */
public final class ObliqueClipping {
    private ObliqueClipping() {}

    /**
     * @param projection a perspective projection (view space looks towards -Z), modified in place
     * @param plane      view-space plane (a, b, c, d); points with a*x + b*y + c*z + d >= 0 are kept.
     *                   The camera must be on the clipped side or on the plane.
     * @param zeroToOne  whether clip-space depth is in [0, 1] (otherwise [-1, 1])
     */
    public static void apply(Matrix4f projection, Vector4f plane, boolean zeroToOne) {
        boolean reversed = isReversedDepth(projection);
        float nearNdc = reversed ? 1 : (zeroToOne ? 0 : -1);
        float farNdc = reversed ? (zeroToOne ? 0 : -1) : 1;

        Vector4f rowW = projection.getRow(3, new Vector4f());

        // the corner of the far plane opposite to the new near plane, in view space
        Matrix4f inverse = projection.invert(new Matrix4f());
        Vector4f clipPlane = new Vector4f(plane);
        // planes transform with the inverse transpose; clip plane = inverse^T * plane
        inverse.transpose(new Matrix4f()).transform(clipPlane);
        Vector4f corner = new Vector4f(Math.signum(clipPlane.x), Math.signum(clipPlane.y), farNdc, 1);
        inverse.transform(corner);

        // kept side: s * (z - nearNdc * w) >= 0
        float s = reversed ? -1 : 1;
        float scale = s * (farNdc - nearNdc) * rowW.dot(corner) / plane.dot(corner);

        Vector4f newRowZ = new Vector4f(rowW).mul(nearNdc).add(new Vector4f(plane).mul(s * scale));
        projection.setRow(2, newRowZ);
    }

    /**
     * Reversed depth maps near points to a larger depth than far points.
     */
    public static boolean isReversedDepth(Matrix4f projection) {
        return ndcDepth(projection, -0.1f) > ndcDepth(projection, -1000f);
    }

    public static float ndcDepth(Matrix4f projection, float viewZ) {
        Vector4f v = projection.transform(new Vector4f(0, 0, viewZ, 1));
        return v.z / v.w;
    }
}
