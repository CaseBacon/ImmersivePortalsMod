package qouteall.imm_ptl.core.portal_view;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ShaderClipPlaneTest {

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    public void distanceFromClipXYWMatchesViewSpaceDistance(boolean reversed, boolean zeroToOne) {
        float fov = (float) Math.toRadians(70);
        Matrix4f projection = reversed
            ? new Matrix4f().setPerspective(fov, 16f / 9f, 1024f, 0.05f, zeroToOne)
            : new Matrix4f().setPerspective(fov, 16f / 9f, 0.05f, 1024f, zeroToOne);
        // an off-centre projection (like a TAA jitter or an asymmetric frustum)
        projection.m20(0.013f).m21(-0.021f);

        Vector3f normal = new Vector3f(0.4f, -0.3f, -0.8f).normalize();
        Vector4f plane = new Vector4f(normal.x, normal.y, normal.z, -normal.dot(new Vector3f(0.5f, 0.2f, -2f)));
        Vector4f k = ShaderClipPlane.clipSpaceCoefficients(projection, plane);

        Random random = new Random(1);
        for (int i = 0; i < 1000; i++) {
            Vector4f view = new Vector4f(
                random.nextFloat() * 40 - 20, random.nextFloat() * 40 - 20, -0.1f - random.nextFloat() * 60, 1
            );
            Vector4f clip = projection.transform(new Vector4f(view));
            float expected = plane.dot(view);
            float actual = k.x * clip.x + k.y * clip.y + k.z * clip.w + k.w;
            assertEquals(expected, actual, 1e-3f * Math.max(1, Math.abs(expected)), "at " + view);
        }
    }
}
