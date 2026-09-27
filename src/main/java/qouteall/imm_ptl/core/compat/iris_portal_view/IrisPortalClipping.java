package qouteall.imm_ptl.core.compat.iris_portal_view;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal_view.ShaderClipPlane;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clips portal views inside the G-buffer programs of an Iris shader pack (see {@link ShaderClipPlane}).
 * <p>
 * The vertex shader of every G-buffer program (not shadow, composite or final programs) computes the signed
 * distance to the clip plane from its final {@code gl_Position}, and the fragment shader discards fragments with a
 * negative distance. The coefficients are the uniform {@value #UNIFORM}; outside of portal views they are
 * {@link ShaderClipPlane#NO_CLIPPING}.
 * <p>
 * Only loaded when Iris is present.
 */
@Environment(EnvType.CLIENT)
public final class IrisPortalClipping {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String UNIFORM = "iportal_ClipCoefficients";
    private static final String VARYING = "iportal_ClipDistance";
    private static final String INNER_MAIN = "iportal_originalMain";
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(void)?\\s*\\)");

    private static final Vector4f coefficients = new Vector4f(ShaderClipPlane.NO_CLIPPING);
    private static @Nullable Runnable listener;

    /**
     * Iris updates a dynamic uniform of the active program when its notifier fires, and when a program is
     * activated.
     */
    public static final ValueUpdateNotifier NOTIFIER = runnable -> listener = runnable;

    private IrisPortalClipping() {}

    public static Vector4f getCoefficients() {
        return new Vector4f(coefficients);
    }

    public static void setCoefficients(@Nullable Vector4fc value) {
        coefficients.set(value == null ? ShaderClipPlane.NO_CLIPPING : value);
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * Adds the clipping to the sources of a G-buffer program (after Iris' own transformation).
     *
     * @return the patched sources, or the input if the program cannot be patched
     */
    public static Map<PatchShaderType, String> patch(String name, Map<PatchShaderType, String> sources) {
        if (sources == null) {
            return null;
        }
        String vertex = sources.get(PatchShaderType.VERTEX);
        String fragment = sources.get(PatchShaderType.FRAGMENT);
        if (vertex == null || fragment == null) {
            return sources;
        }
        if (sources.get(PatchShaderType.GEOMETRY) != null
            || sources.get(PatchShaderType.TESS_CONTROL) != null
            || sources.get(PatchShaderType.TESS_EVAL) != null) {
            // the distance would have to be passed through the other stages
            LOGGER.warn("Not clipping portal views in the shader program {}: it has geometry or tessellation stages", name);
            return sources;
        }
        if (vertex.contains(INNER_MAIN)) {
            return sources;
        }

        String patchedVertex = renameMain(vertex);
        String patchedFragment = renameMain(fragment);
        if (patchedVertex == null || patchedFragment == null) {
            LOGGER.warn("Not clipping portal views in the shader program {}: no unique main function", name);
            return sources;
        }

        patchedVertex += "\nuniform vec4 " + UNIFORM + ";\n"
            + "out float " + VARYING + ";\n"
            + "void main() {\n"
            + "    " + INNER_MAIN + "();\n"
            + "    " + VARYING + " = dot(" + UNIFORM + ".xyz, gl_Position.xyw) + " + UNIFORM + ".w;\n"
            + "}\n";
        patchedFragment += "\nin float " + VARYING + ";\n"
            + "void main() {\n"
            + "    if (" + VARYING + " < 0.0) {\n"
            + "        discard;\n"
            + "    }\n"
            + "    " + INNER_MAIN + "();\n"
            + "}\n";

        Map<PatchShaderType, String> result = new EnumMap<>(PatchShaderType.class);
        result.putAll(sources);
        result.put(PatchShaderType.VERTEX, patchedVertex);
        result.put(PatchShaderType.FRAGMENT, patchedFragment);
        return result;
    }

    private static @Nullable String renameMain(String source) {
        Matcher matcher = MAIN.matcher(source);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.start(), end = matcher.end();
        if (matcher.find()) {
            return null;
        }
        return source.substring(0, start) + "void " + INNER_MAIN + "()" + source.substring(end);
    }
}
