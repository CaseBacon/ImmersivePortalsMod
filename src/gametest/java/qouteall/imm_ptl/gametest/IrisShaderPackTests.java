package qouteall.imm_ptl.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.portal_view.PortalViewRenderer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Portal views and teleportation with Iris shader packs. Skipped when Iris is not loaded.
 * <p>
 * Runs with a minimal shader pack that the test writes (no programs; Iris uses its fallback programs) and with
 * every Complementary Shaders zip found in the shaderpacks directory of the run directory. Those are not part of
 * the repository; without them only the minimal pack is tested.
 */
public class IrisShaderPackTests implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(IrisShaderPackTests.class);
    private static final String MINIMAL_PACK = "imm_ptl_test_pack";

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!FabricLoader.getInstance().isModLoaded("iris")) {
            LOGGER.warn("SKIPPED IrisShaderPackTests: Iris is not loaded");
            return;
        }

        List<String> packs = new ArrayList<>();
        packs.add(context.computeOnClient(mc -> IrisControl.writeMinimalPack()));
        packs.addAll(context.computeOnClient(mc -> IrisControl.findPacks("Complementary")));
        if (packs.size() == 1) {
            LOGGER.warn("IrisShaderPackTests: no Complementary Shaders zip in the shaderpacks directory; only the minimal pack is tested");
        }

        try {
            for (String pack : packs) {
                testPack(context, pack);
            }
        }
        finally {
            context.runOnClient(mc -> IrisControl.setShaderPack(null));
        }
    }

    private static void testPack(ClientGameTestContext context, String pack) {
        LOGGER.info("IrisShaderPackTests: testing {}", pack);
        String label = pack.replaceAll("[^A-Za-z0-9.]+", "_");
        context.runOnClient(mc -> IrisControl.setShaderPack(pack));
        check(context.computeOnClient(mc -> IrisInterface.invoker.isShaders()), "the shader pack is not in use: " + pack);

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            int surfaceY = singleplayer.getServer().computeOnServer(
                server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0)
            );
            // the nether test room of ClientPortalTests: glowstone floor, gold wall ahead
            singleplayer.getServer().runCommand("execute in minecraft:the_nether run forceload add 0 0");
            singleplayer.getServer().runCommand("execute in minecraft:the_nether run fill -3 99 -3 3 105 3 minecraft:obsidian");
            singleplayer.getServer().runCommand("execute in minecraft:the_nether run fill -2 100 -2 2 104 2 minecraft:air");
            singleplayer.getServer().runCommand("execute in minecraft:the_nether run fill -2 99 -2 2 99 2 minecraft:glowstone");
            singleplayer.getServer().runCommand("execute in minecraft:the_nether run fill -3 99 -3 3 105 -3 minecraft:gold_block");
            singleplayer.getServer().runCommand("time set noon");
            singleplayer.getServer().runCommand("tp @a 0.5 " + surfaceY + " 0.5 180 0");
            singleplayer.getConnection().waitForClientboundPackets();
            UUID portalId = singleplayer.getServer().computeOnServer(server -> TestUtil.spawnPortal(
                server.overworld(), new Vec3(0.5, surfaceY + 1.5, -2.5), Level.NETHER, new Vec3(0.5, 101.5, 0.5)
            ).getUUID());

            context.waitFor(mc -> {
                ClientLevel nether = findClientWorld(Level.NETHER);
                if (nether == null) {
                    return false;
                }
                for (int x = -2; x <= 2; x++) {
                    for (int z = -2; z <= 2; z++) {
                        if (nether.getChunkSource().getChunk(x, z, false) == null) {
                            return false;
                        }
                    }
                }
                return true;
            }, 1200);
            context.waitFor(mc -> ClientWorldLoader.getWorldRenderer(Level.NETHER).hasRenderedAllSections(), 600);
            context.waitTicks(40);

            int far = pixel(context.takeScreenshot("imm_ptl_iris_" + label + "_01_portal"));
            check(
                context.computeOnClient(mc -> PortalViewRenderer.isViewDrawnThisFrame(portalId)),
                "the portal view was not drawn with " + pack
            );
            LOGGER.info("IrisShaderPackTests {}: pixel in the portal {}", pack, describe(far));
            // the minimal pack draws the world black, so pixels are only checked with real packs
            boolean checkPixels = !pack.equals(MINIMAL_PACK);
            if (checkPixels) {
                check(isGold(far), "the gold wall is not visible in the portal with " + pack + ": " + describe(far));
            }

            singleplayer.getServer().runCommand("tp @a 0.5 " + surfaceY + " -2.47 180 0");
            singleplayer.getConnection().waitForClientboundPackets();
            context.waitTicks(10);
            int close = pixel(context.takeScreenshot("imm_ptl_iris_" + label + "_02_close"));
            LOGGER.info("IrisShaderPackTests {}: pixel right in front of the portal {}", pack, describe(close));
            if (checkPixels) {
                check(isGold(close), "right in front of the portal, the gold wall is not visible with " + pack + ": " + describe(close));
            }
            singleplayer.getServer().runCommand("tp @a 0.5 " + surfaceY + " 0.5 180 0");
            singleplayer.getConnection().waitForClientboundPackets();
            context.waitTicks(10);

            // a block between the player and the portal hides the portal
            // (the portal area is depth tested against the main target after Iris' final pass)
            singleplayer.getServer().runCommand("fill 0 " + (surfaceY + 1) + " -1 0 " + (surfaceY + 2) + " -1 minecraft:stone");
            singleplayer.getConnection().waitForClientboundPackets();
            context.waitTicks(10);
            int occluded = pixel(context.takeScreenshot("imm_ptl_iris_" + label + "_02b_occluded"));
            LOGGER.info("IrisShaderPackTests {}: pixel with a block in front of the portal {}", pack, describe(occluded));
            if (checkPixels) {
                check(!isGold(occluded), "the portal is drawn over a block in front of it with " + pack + ": " + describe(occluded));
            }
            singleplayer.getServer().runCommand("fill 0 " + (surfaceY + 1) + " -1 0 " + (surfaceY + 2) + " -1 minecraft:air");
            // something to cast a shadow at the overworld destination of the return portal
            singleplayer.getServer().runCommand("fill 3 " + surfaceY + " 6 3 " + (surfaceY + 6) + " 6 minecraft:stone");

            // teleportation does not depend on the portal view
            context.getInput().holdKeyFor(options -> options.keyUp, 40);
            singleplayer.getServer().waitFor(
                server -> server.getPlayerList().getPlayers().getFirst().level().dimension() == Level.NETHER, 200
            );
            context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.NETHER, 200);
            context.waitTicks(20);
            context.takeScreenshot("imm_ptl_iris_" + label + "_03_in_nether");

            // look back into the overworld (sky, sun and shadows) through a portal in the nether room
            singleplayer.getServer().runCommand("execute in minecraft:the_nether run tp @a 0.5 100 1.5 180 0");
            singleplayer.getConnection().waitForClientboundPackets();
            context.waitTicks(5);
            UUID returnPortalId = singleplayer.getServer().computeOnServer(server -> TestUtil.spawnPortal(
                server.getLevel(Level.NETHER), new Vec3(0.5, 101.5, -1.0), Level.OVERWORLD,
                new Vec3(0.5, surfaceY + 1.5, 12.5)
            ).getUUID());
            context.waitTicks(60);
            context.takeScreenshot("imm_ptl_iris_" + label + "_04_overworld_from_nether");
            check(
                context.computeOnClient(mc -> PortalViewRenderer.isViewDrawnThisFrame(returnPortalId)),
                "the view into the overworld was not drawn with " + pack
            );
        }
    }

    private static int pixel(Path screenshot) {
        try (InputStream in = Files.newInputStream(screenshot); NativeImage image = NativeImage.read(in)) {
            // next to the crosshair
            return image.getPixel(image.getWidth() / 2 + image.getWidth() / 16, image.getHeight() / 2);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // the gold block wall of the nether test room
    private static boolean isGold(int argb) {
        int r = ARGB.red(argb), g = ARGB.green(argb), b = ARGB.blue(argb);
        return r > 150 && g > 100 && b < r * 0.6;
    }

    private static String describe(int argb) {
        return "rgb(%d, %d, %d)".formatted(ARGB.red(argb), ARGB.green(argb), ARGB.blue(argb));
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

    /**
     * Only loaded when Iris is present.
     */
    static final class IrisControl {
        static String writeMinimalPack() {
            try {
                Path shaders = Iris.getShaderpacksDirectory().resolve(MINIMAL_PACK).resolve("shaders");
                Files.createDirectories(shaders);
                Files.writeString(shaders.resolve("shaders.properties"), "# minimal pack for Immersive Portals tests\n");
                return MINIMAL_PACK;
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        static List<String> findPacks(String prefix) {
            try (Stream<Path> files = Files.list(Iris.getShaderpacksDirectory())) {
                return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".zip"))
                    .sorted()
                    .toList();
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * @param pack the pack to use, or null to disable shaders
         */
        static void setShaderPack(String pack) {
            try {
                Iris.getIrisConfig().setShaderPackName(pack);
                Iris.getIrisConfig().setShadersEnabled(pack != null);
                Iris.getIrisConfig().save();
                Iris.reload();
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
