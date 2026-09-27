package qouteall.imm_ptl.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal_view.PortalAreaMesh;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compares what a portal shows with the ground truth: for a set of camera poses near a portal, the view through
 * the portal is compared with the destination rendered directly from the corresponding camera pose. The space
 * between that camera and the destination plane is empty, so inside the portal's screen area both images should
 * be the same; differences are errors of the portal view (projection, clipping, depth, shading).
 * <p>
 * Runs without shaders and, when Iris is loaded, with every Complementary Shaders zip in the shaderpacks
 * directory. Reports the differences per pose (log, and difference images in the screenshots directory) and fails
 * when more than 1% of the portal area differs by more than 32/255, or without shaders when the mean difference
 * exceeds 2/255.
 */
public class PortalViewAccuracyTests implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(PortalViewAccuracyTests.class);

    private static final double EYE_HEIGHT = 1.62;
    private static final double PORTAL_WIDTH = 4;
    private static final double PORTAL_HEIGHT = 3;
    private static final Vec3 NETHER_DESTINATION = new Vec3(0.5, 108.5, 0.5);

    /**
     * Eye position relative to the portal origin (the portal lies in the XY plane and faces +Z) and camera angles.
     */
    record Pose(String name, double dx, double dy, double dz, float yaw, float pitch) {}

    static final List<Pose> POSES = List.of(
        new Pose("front_far", 0, 0, 4, 180, 0),
        new Pose("front_near", 0, 0, 1, 180, 0),
        new Pose("front_close", 0, 0, 0.25, 180, 0),
        new Pose("side_right", 2.5, 0, 2, 150, 0),
        new Pose("side_left_up", -2, 0.8, 1.5, 215, 12),
        new Pose("grazing_right", 3.5, -0.5, 0.8, 125, -8),
        new Pose("close_oblique", 0.8, 0.2, 0.2, 230, 5),
        new Pose("close_steep", -0.4, 1.0, 0.5, 165, 35),
        new Pose("below_up", 0.5, -1.2, 1.2, 190, -40)
    );

    record Result(String pack, Pose pose, double meanDiff, double badFraction, int pixels) {}

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        boolean iris = FabricLoader.getInstance().isModLoaded("iris");
        List<String> packs = new ArrayList<>();
        packs.add(null);
        if (iris) {
            packs.addAll(context.computeOnClient(mc -> IrisShaderPackTests.IrisControl.findPacks("Complementary")));
        }

        List<Result> results = new ArrayList<>();
        try {
            for (String pack : packs) {
                if (iris) {
                    context.runOnClient(mc -> IrisShaderPackTests.IrisControl.setShaderPack(pack));
                }
                results.addAll(measure(context, pack == null ? "no_shaders" : pack));
            }
        }
        finally {
            if (iris) {
                context.runOnClient(mc -> IrisShaderPackTests.IrisControl.setShaderPack(null));
            }
            context.runOnClient(mc -> setHudHidden(mc, false));
        }

        StringBuilder report = new StringBuilder("[imm_ptl accuracy] pack pose meanDiff badFraction pixels\n");
        for (Result r : results) {
            report.append(String.format(Locale.ROOT, "[imm_ptl accuracy] %s %s %.2f %.4f %d%n",
                r.pack, r.pose.name, r.meanDiff, r.badFraction, r.pixels));
        }
        LOGGER.info("\n{}", report);
    }

    private static List<Result> measure(ClientGameTestContext context, String packLabel) {
        String label = packLabel.replaceAll("[^A-Za-z0-9.]+", "_");
        List<Result> results = new ArrayList<>();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            int surfaceY = singleplayer.getServer().computeOnServer(
                server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0)
            );
            Vec3 origin = new Vec3(0.5, surfaceY + 2.5, -2.5);
            singleplayer.getServer().runCommand("gamemode spectator @a");
            singleplayer.getServer().runCommand("time set noon");
            buildNetherScene(singleplayer);
            context.runOnClient(mc -> setHudHidden(mc, true));

            teleportToPose(singleplayer, "minecraft:overworld", origin, POSES.getFirst());
            singleplayer.getServer().computeOnServer(server -> TestUtil.spawnPortal(
                server.overworld(), origin, Level.NETHER, NETHER_DESTINATION, PORTAL_WIDTH, PORTAL_HEIGHT
            ));
            waitForNether(context);

            // through the portal
            List<Path> throughPortal = new ArrayList<>();
            List<float[]> masks = new ArrayList<>();
            for (Pose pose : POSES) {
                teleportToPose(singleplayer, "minecraft:overworld", origin, pose);
                singleplayer.getConnection().waitForClientboundPackets();
                context.waitTicks(30);
                masks.add(context.computeOnClient(mc -> portalScreenTriangles(mc.gameRenderer.mainCamera(),
                    mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix, origin)));
                throughPortal.add(context.takeScreenshot("imm_ptl_acc_" + label + "_" + pose.name + "_portal"));
            }

            // the same camera poses directly in the nether
            teleportToPose(singleplayer, "minecraft:the_nether", NETHER_DESTINATION, POSES.getFirst());
            context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.NETHER
                && mc.player != null && mc.player.level() == mc.level, 200);
            context.waitFor(mc -> mc.levelRenderer.hasRenderedAllSections(), 1200);
            context.waitTicks(40);
            for (int i = 0; i < POSES.size(); i++) {
                Pose pose = POSES.get(i);
                teleportToPose(singleplayer, "minecraft:the_nether", NETHER_DESTINATION, pose);
                singleplayer.getConnection().waitForClientboundPackets();
                context.waitTicks(30);
                Path direct = context.takeScreenshot("imm_ptl_acc_" + label + "_" + pose.name + "_direct");
                Result result = compare(packLabel, pose, throughPortal.get(i), direct, masks.get(i),
                    direct.resolveSibling("imm_ptl_acc_" + label + "_" + pose.name + "_diff.png"));
                LOGGER.info(String.format(Locale.ROOT, "[imm_ptl accuracy] %s %s mean %.2f bad %.4f of %d",
                    packLabel, pose.name, result.meanDiff, result.badFraction, result.pixels));
                check(result.pixels > 0, "the portal is not on screen for pose " + pose.name);
                // Before shader packs were clipped in their G-buffer programs, the oblique projection broke their depth
                // based effects: 20% of the pixels were off by more than 32/255 at close_oblique with Complementary.
                // Remaining differences with shader packs come from uniforms taken from the player entity
                // (eye brightness, biome) and temporal effects; they stay far below that.
                check(result.badFraction < 0.01, String.format(Locale.ROOT,
                    "%s: %.1f%% of the portal differs from the direct view at pose %s",
                    packLabel, result.badFraction * 100, pose.name));
                if (packLabel.equals("no_shaders")) {
                    check(result.meanDiff < 2, String.format(Locale.ROOT,
                        "without shaders the portal view differs from the direct view at pose %s (mean %.2f)",
                        pose.name, result.meanDiff));
                }
                results.add(result);
            }
        }
        return results;
    }

    private static void buildNetherScene(TestSingleplayerContext singleplayer) {
        // a closed room around the destination (within 2 chunks of it, like the chunks sent for a portal view):
        // glowstone floor, empty space behind the destination plane (+Z), coloured pillars and walls in front (-Z)
        String[] commands = {
            "forceload add -32 -32 31 31",
            "fill -14 97 -15 14 119 10 minecraft:obsidian hollow",
            "fill -13 98 -14 13 98 9 minecraft:glowstone",
            "fill -13 99 -14 13 118 -14 minecraft:yellow_wool",
            "fill -6 99 -10 -5 115 -9 minecraft:red_wool",
            "fill 5 99 -7 6 112 -6 minecraft:blue_wool",
            "fill -1 99 -4 0 104 -3 minecraft:lime_wool",
            "fill -13 110 -12 13 111 -12 minecraft:white_wool",
            "fill 9 99 -13 12 109 -11 minecraft:orange_wool",
            "fill -12 99 -6 -9 103 -4 minecraft:magenta_wool",
            "fill -13 116 -14 13 118 -2 minecraft:light_blue_wool",
        };
        for (String command : commands) {
            singleplayer.getServer().runCommand("execute in minecraft:the_nether run " + command);
        }
    }

    private static void waitForNether(ClientGameTestContext context) {
        context.waitFor(mc -> {
            ClientLevel nether = findClientWorld(Level.NETHER);
            if (nether == null) {
                return false;
            }
            // the room is within chunks -1..0; sections compile when their neighbour chunks are there too
            for (int x = -2; x <= 1; x++) {
                for (int z = -2; z <= 1; z++) {
                    if (nether.getChunkSource().getChunk(x, z, false) == null) {
                        return false;
                    }
                }
            }
            return true;
        }, 1200);
        context.waitFor(mc -> ClientWorldLoader.getWorldRenderer(Level.NETHER).hasRenderedAllSections(), 1200);
        context.waitTicks(40);
    }

    private static void teleportToPose(TestSingleplayerContext singleplayer, String dimension, Vec3 origin, Pose pose) {
        Vec3 feet = origin.add(pose.dx, pose.dy - EYE_HEIGHT, pose.dz);
        singleplayer.getServer().runCommand(String.format(Locale.ROOT,
            "execute in %s run tp @a %.4f %.4f %.4f %.2f %.2f", dimension, feet.x, feet.y, feet.z, pose.yaw, pose.pitch));
    }

    /**
     * The portal's screen area as triangles in normalized screen coordinates (x right, y down, 0..1).
     */
    private static float[] portalScreenTriangles(Camera camera, Matrix4f projection, Vec3 origin) {
        Matrix4f viewRotation = camera.getViewRotationMatrix(new Matrix4f());
        Vec3 c = origin.subtract(camera.position());
        double hw = PORTAL_WIDTH / 2, hh = PORTAL_HEIGHT / 2;
        Vector3f[] corners = {
            new Vector3f((float) (c.x - hw), (float) (c.y - hh), (float) c.z),
            new Vector3f((float) (c.x + hw), (float) (c.y - hh), (float) c.z),
            new Vector3f((float) (c.x + hw), (float) (c.y + hh), (float) c.z),
            new Vector3f((float) (c.x - hw), (float) (c.y + hh), (float) c.z),
        };
        for (Vector3f corner : corners) {
            viewRotation.transformPosition(corner);
        }
        PortalAreaMesh mesh = PortalAreaMesh.create();
        mesh.addCloseProjectedTriangle(corners[0], corners[1], corners[2], 0.06f);
        mesh.addCloseProjectedTriangle(corners[0], corners[2], corners[3], 0.06f);
        float[] result = new float[mesh.vertexCount() * 2];
        for (int i = 0; i < mesh.vertexCount(); i++) {
            Vector3f v = mesh.getVertex(i, new Vector3f());
            Vector4f clip = projection.transform(new Vector4f(v, 1));
            result[i * 2] = (clip.x / clip.w) * 0.5f + 0.5f;
            result[i * 2 + 1] = 0.5f - (clip.y / clip.w) * 0.5f;
        }
        return result;
    }

    private static boolean inside(float[] triangles, float x, float y) {
        for (int t = 0; t + 5 < triangles.length; t += 6) {
            float x0 = triangles[t], y0 = triangles[t + 1], x1 = triangles[t + 2], y1 = triangles[t + 3];
            float x2 = triangles[t + 4], y2 = triangles[t + 5];
            float d0 = (x1 - x0) * (y - y0) - (y1 - y0) * (x - x0);
            float d1 = (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1);
            float d2 = (x0 - x2) * (y - y2) - (y0 - y2) * (x - x2);
            boolean neg = d0 < 0 || d1 < 0 || d2 < 0;
            boolean pos = d0 > 0 || d1 > 0 || d2 > 0;
            if (!(neg && pos)) {
                return true;
            }
        }
        return false;
    }

    private static Result compare(String pack, Pose pose, Path portal, Path direct, float[] triangles, Path diffOut) {
        try (InputStream a = Files.newInputStream(portal); InputStream b = Files.newInputStream(direct);
             NativeImage imageA = NativeImage.read(a); NativeImage imageB = NativeImage.read(b);
             NativeImage diff = new NativeImage(imageA.getWidth(), imageA.getHeight(), false)) {
            int w = imageA.getWidth(), h = imageA.getHeight();
            int margin = 6;
            double sum = 0;
            int pixels = 0, bad = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int pa = imageA.getPixel(x, y), pb = imageB.getPixel(x, y);
                    int d = (Math.abs(ARGB.red(pa) - ARGB.red(pb)) + Math.abs(ARGB.green(pa) - ARGB.green(pb))
                        + Math.abs(ARGB.blue(pa) - ARGB.blue(pb))) / 3;
                    // a margin around the portal edge (the screen area is computed, not rasterized)
                    boolean in = inside(triangles, (x - margin) / (float) w, y / (float) h)
                        && inside(triangles, (x + margin) / (float) w, y / (float) h)
                        && inside(triangles, x / (float) w, (y - margin) / (float) h)
                        && inside(triangles, x / (float) w, (y + margin) / (float) h);
                    if (in) {
                        sum += d;
                        pixels++;
                        if (d > 32) {
                            bad++;
                        }
                        int v = Math.min(255, d * 4);
                        diff.setPixel(x, y, ARGB.color(255, v, v, v));
                    }
                    else {
                        diff.setPixel(x, y, ARGB.color(255, 40, 0, 0));
                    }
                }
            }
            diff.writeToFile(diffOut);
            return new Result(pack, pose, pixels == 0 ? 0 : sum / pixels, pixels == 0 ? 0 : bad / (double) pixels, pixels);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // like F1: no HUD, no hand
    private static void setHudHidden(net.minecraft.client.Minecraft mc, boolean hidden) {
        if (mc.gui.hud.isHidden() != hidden) {
            mc.gui.hud.toggle();
        }
    }

    private static ClientLevel findClientWorld(ResourceKey<Level> dimension) {
        if (!ClientWorldLoader.getIsInitialized()) {
            return null;
        }
        for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
            if (world.dimension() == dimension) {
                return world;
            }
        }
        return null;
    }
}
