package qouteall.imm_ptl.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The client connects to an in-process dedicated server over a real network connection
 * (not the integrated server), and goes through a portal into the nether and back.
 * <p>
 * Dedicated servers only start when the Minecraft EULA has been accepted in {@code eula.txt} of the run
 * directory ({@code build/clientgametest/eula.txt}). Accepting it is up to whoever runs the tests, so without
 * that file this test logs that it was skipped and passes.
 */
public class DedicatedServerPortalTests implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(DedicatedServerPortalTests.class);

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static boolean isEulaAccepted() {
        Path eula = Path.of("eula.txt");
        try {
            return Files.isRegularFile(eula)
                && Files.readAllLines(eula).stream().anyMatch(line -> line.trim().equals("eula=true"));
        }
        catch (IOException e) {
            return false;
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!isEulaAccepted()) {
            LOGGER.warn(
                "SKIPPED DedicatedServerPortalTests: the Minecraft EULA is not accepted in {}",
                Path.of("eula.txt").toAbsolutePath()
            );
            return;
        }

        try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
            int surfaceY = server.computeOnServer(
                s -> s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0)
            );
            server.runCommand("execute in minecraft:the_nether run forceload add 0 0");
            server.runCommand("execute in minecraft:the_nether run fill -3 99 -3 3 105 3 minecraft:obsidian");
            server.runCommand("execute in minecraft:the_nether run fill -2 100 -2 2 104 2 minecraft:air");

            try (TestDedicatedServerConnection connection = server.connect()) {
                connection.waitForChunksRender();
                server.runCommand("tp @a 0.5 " + surfaceY + " 0.5 180 0");
                connection.waitForClientboundPackets();
                context.waitTicks(5);

                UUID portalId = server.computeOnServer(s -> TestUtil.spawnPortal(
                    s.overworld(),
                    new Vec3(0.5, surfaceY + 1.5, -2.5),
                    Level.NETHER,
                    new Vec3(0.5, 101.5, 0.5)
                ).getUUID());

                // the portal entity and the chunks behind it arrive over the network
                context.waitFor(mc -> mc.level != null && findEntity(mc.level, portalId) instanceof Portal, 200);
                context.waitFor(mc -> {
                    ClientLevel nether = findClientWorld(Level.NETHER);
                    return nether != null && nether.getChunkSource().getLoadedChunksCount() > 0;
                }, 600);
                check(
                    context.computeOnClient(mc -> mc.level.dimension() == Level.OVERWORLD),
                    "loading the nether client world must not change the current client world"
                );
                context.takeScreenshot("imm_ptl_ds_01_portal");

                // walk through the portal
                context.getInput().holdKeyFor(options -> options.keyUp, 40);
                server.waitFor(s -> s.getPlayerList().getPlayers().getFirst().level().dimension() == Level.NETHER, 200);
                context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.NETHER
                    && mc.player != null && mc.player.level() == mc.level, 200);
                Vec3 serverPos = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().position());
                check(serverPos.distanceTo(new Vec3(0.5, 101.5, 0.5)) < 8, "unexpected nether position " + serverPos);
                context.takeScreenshot("imm_ptl_ds_02_in_nether");

                // back with a vanilla dimension change
                server.runCommand("execute in minecraft:overworld run tp @a 0.5 " + surfaceY + " 6.5 0 0");
                context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.OVERWORLD
                    && mc.player != null && mc.player.level() == mc.level, 200);
                context.waitFor(mc -> mc.gui.screen() == null, 1200);
                context.takeScreenshot("imm_ptl_ds_03_back");
            }

            check(
                context.computeOnClient(mc -> !ClientWorldLoader.getIsInitialized()),
                "client worlds were not cleaned up after disconnecting"
            );
        }
    }

    private static Entity findEntity(ClientLevel level, UUID uuid) {
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.getUUID().equals(uuid)) {
                return entity;
            }
        }
        return null;
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
