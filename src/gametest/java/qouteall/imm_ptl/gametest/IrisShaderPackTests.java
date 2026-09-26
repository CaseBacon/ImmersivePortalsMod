package qouteall.imm_ptl.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.portal_view.PortalViewRenderer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * With an Iris shader pack in use, portal views are not drawn (see PortalViewRenderer), but teleportation
 * still works. The test writes a minimal shader pack (no programs, so Iris uses its fallback programs),
 * enables it, goes through a portal and disables it again. Skipped when Iris is not loaded.
 */
public class IrisShaderPackTests implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(IrisShaderPackTests.class);
    private static final String PACK_NAME = "imm_ptl_test_pack";

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

        context.runOnClient(mc -> IrisControl.setShaderPack(true));
        try {
            check(context.computeOnClient(mc -> IrisInterface.invoker.isShaders()), "the test shader pack is not in use");

            try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
                singleplayer.getConnection().waitForChunksRender();
                int surfaceY = singleplayer.getServer().computeOnServer(
                    server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0)
                );
                singleplayer.getServer().runCommand("execute in minecraft:the_nether run forceload add 0 0");
                singleplayer.getServer().runCommand("execute in minecraft:the_nether run fill -3 99 -3 3 105 3 minecraft:obsidian");
                singleplayer.getServer().runCommand("execute in minecraft:the_nether run fill -2 100 -2 2 104 2 minecraft:air");
                singleplayer.getServer().runCommand("tp @a 0.5 " + surfaceY + " 0.5 180 0");
                singleplayer.getConnection().waitForClientboundPackets();
                singleplayer.getServer().computeOnServer(server -> TestUtil.spawnPortal(
                    server.overworld(), new Vec3(0.5, surfaceY + 1.5, -2.5), Level.NETHER, new Vec3(0.5, 101.5, 0.5)
                ));
                context.waitFor(mc -> mc.levelRenderer.hasRenderedAllSections(), 600);
                context.waitTicks(40);
                context.takeScreenshot("imm_ptl_iris_01_shader_pack");
                check(
                    context.computeOnClient(mc -> PortalViewRenderer.getViewCountThisFrame() == 0),
                    "portal views must not be drawn while a shader pack is in use"
                );

                // teleportation does not depend on the portal view
                context.getInput().holdKeyFor(options -> options.keyUp, 40);
                singleplayer.getServer().waitFor(
                    server -> server.getPlayerList().getPlayers().getFirst().level().dimension() == Level.NETHER, 200
                );
                context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.NETHER, 200);
                context.takeScreenshot("imm_ptl_iris_02_in_nether");
            }
        }
        finally {
            context.runOnClient(mc -> IrisControl.setShaderPack(false));
        }
    }

    /**
     * Only loaded when Iris is present.
     */
    private static final class IrisControl {
        static void setShaderPack(boolean enabled) {
            try {
                Path shaders = Iris.getShaderpacksDirectory().resolve(PACK_NAME).resolve("shaders");
                Files.createDirectories(shaders);
                Files.writeString(shaders.resolve("shaders.properties"), "# minimal pack for Immersive Portals tests\n");
                Iris.getIrisConfig().setShaderPackName(enabled ? PACK_NAME : null);
                Iris.getIrisConfig().setShadersEnabled(enabled);
                Iris.getIrisConfig().save();
                Iris.reload();
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
