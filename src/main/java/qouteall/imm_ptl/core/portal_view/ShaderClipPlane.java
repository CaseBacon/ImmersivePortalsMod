package qouteall.imm_ptl.core.portal_view;

import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * Clipping against a view-space plane inside shaders, for shader packs.
 * <p>
 * {@link ObliqueClipping} changes the depth row of the projection. The rasterizer does not mind, but shader packs
 * reconstruct distances from depth with the standard perspective formula ({@code near}/{@code far},
 * {@code gbufferProjectionInverse}), so fog, sky detection and lighting go wrong in the view, more so the more
 * oblique the plane is (camera close to and at an angle to the portal). With a shader pack the view keeps the
 * standard projection, and the G-buffer shaders discard fragments behind the plane instead.
 * <p>
 * The vertex shader only has the clip-space position, whose depth component shader packs and Iris may rewrite.
 * For a perspective projection ({@code w_clip = -z_view}, no shear in x and y) the view-space position follows
 * from x, y and w alone:
 * <pre>
 *     z = -w,   x = (x_clip + m20 w) / m00,   y = (y_clip + m21 w) / m11
 * </pre>
 * so the signed distance to the plane (a, b, c, d) is an affine function of (x_clip, y_clip, w_clip), which also
 * interpolates exactly across a triangle:
 * <pre>
 *     distance = k.x * x_clip + k.y * y_clip + k.z * w_clip + k.w
 * </pre>
 */
public final class ShaderClipPlane {
    private ShaderClipPlane() {}

    /**
     * Coefficients that keep everything (distance 1 everywhere).
     */
    public static final Vector4fc NO_CLIPPING = new Vector4f(0, 0, 0, 1);

    /**
     * @param projection the perspective projection the shaders use for x and y (depth convention does not matter)
     * @param plane      view-space plane (a, b, c, d); points with a*x + b*y + c*z + d >= 0 are kept
     * @return k, see the class comment
     */
    public static Vector4f clipSpaceCoefficients(Matrix4fc projection, Vector4fc plane) {
        float m00 = projection.m00(), m11 = projection.m11();
        float m20 = projection.m20(), m21 = projection.m21();
        return new Vector4f(
            plane.x() / m00,
            plane.y() / m11,
            plane.x() * m20 / m00 + plane.y() * m21 / m11 - plane.z(),
            plane.w()
        );
    }
}
