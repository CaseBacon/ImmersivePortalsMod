package qouteall.imm_ptl.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.UUID;

/**
 * Drives a real client in a singleplayer world:
 * the destination dimension of a portal is loaded as a second client world,
 * walking through the portal changes the client and server dimension seamlessly,
 * and a vanilla cross-dimension teleport works with several client worlds.
 * <p>
 * Portal rendering is not ported yet (RendererDummy), so the screenshots only show the
 * current dimension. They are saved in the run directory for manual review.
 */
public class ClientPortalTests implements FabricClientGameTest {

    private static ServerPlayer onlyPlayer(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerConnection connection = singleplayer.getConnection();
            connection.waitForChunksRender();

            int surfaceY = singleplayer.getServer().computeOnServer(
                server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0)
            );

            // a closed obsidian room at the destination in the nether (no lava can flow in)
            singleplayer.getServer().runCommand("execute in minecraft:the_nether run forceload add 0 0");
            singleplayer.getServer().runCommand("execute in minecraft:the_nether run fill -3 99 -3 3 105 3 minecraft:obsidian");
            singleplayer.getServer().runCommand("execute in minecraft:the_nether run fill -2 100 -2 2 104 2 minecraft:air");

            // stand at (0.5, surface, 0.5) looking north (-Z)
            singleplayer.getServer().runCommand("tp @a 0.5 " + surfaceY + " 0.5 180 0");
            connection.waitForClientboundPackets();
            context.waitTicks(5);

            // a portal 3 blocks north of the player, facing the player, leading into the nether
            UUID portalId = singleplayer.getServer().computeOnServer(server -> TestUtil.spawnPortal(
                server.overworld(),
                new Vec3(0.5, surfaceY + 1.5, -2.5),
                Level.NETHER,
                new Vec3(0.5, 101.5, 0.5)
            ).getUUID());

            // the portal entity reaches the client
            context.waitFor(mc -> mc.level != null && findEntity(mc.level, portalId) instanceof Portal, 200);

            // chunks behind the portal are sent, which creates a second client world for the nether
            context.waitFor(mc -> {
                ClientLevel nether = findClientWorld(Level.NETHER);
                return nether != null && nether.getChunkSource().getLoadedChunksCount() > 0;
            }, 600);
            context.takeScreenshot("imm_ptl_01_portal_in_overworld");
            check(
                context.computeOnClient(mc -> mc.level.dimension() == Level.OVERWORLD),
                "loading the nether client world must not change the current client world"
            );

            // walk through the portal
            context.getInput().holdKeyFor(options -> options.keyUp, 40);
            singleplayer.getServer().waitFor(server -> onlyPlayer(server).level().dimension() == Level.NETHER, 200);
            context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.NETHER, 200);
            waitForRenderedChunks(context, "nether after walking through the portal");
            context.takeScreenshot("imm_ptl_02_after_walking_through_portal");

            Vec3 serverPos = singleplayer.getServer().computeOnServer(server -> onlyPlayer(server).position());
            check(serverPos.distanceTo(new Vec3(0.5, 101.5, 0.5)) < 8, "unexpected nether position " + serverPos);
            check(
                context.computeOnClient(mc -> mc.player.level() == mc.level),
                "client player is not in the current client world"
            );

            // a conventional (vanilla) dimension change while several client worlds exist
            singleplayer.getServer().runCommand(
                "execute in minecraft:overworld run tp @a 0.5 " + surfaceY + " 6.5 0 0"
            );
            singleplayer.getServer().waitFor(server -> onlyPlayer(server).level().dimension() == Level.OVERWORLD, 200);
            context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.OVERWORLD && mc.player != null
                && mc.player.level() == mc.level, 200);
            waitForRenderedChunks(context, "overworld after the vanilla teleport");
            // the loading screen closes when the player's section is compiled; vanilla lets the player in
            // anyway after 30 seconds, which would mean the compiled-section callback does not arrive
            int loadingTicks = context.waitFor(mc -> mc.gui.screen() == null, 1200);
            check(loadingTicks < 400, "loading screen only closed after " + loadingTicks + " ticks");
            context.takeScreenshot("imm_ptl_03_back_in_overworld");
        }

        // leaving the world disposes all client worlds
        check(
            context.computeOnClient(mc -> !ClientWorldLoader.getIsInitialized()),
            "client worlds were not cleaned up after leaving"
        );
    }

    /**
     * Like TestServerConnection.waitForChunksRender, but reports which condition is missing on timeout.
     */
    private static void waitForRenderedChunks(ClientGameTestContext context, String label) {
        try {
            context.waitFor(mc -> mc.level != null
                && mc.level.getChunkSource().getLoadedChunksCount() > 0
                && mc.levelRenderer.hasRenderedAllSections(), 1200);
        }
        catch (AssertionError e) {
            String state = context.computeOnClient(mc -> "level=%s loadedChunks=%d allSectionsRendered=%s playerChunkLoaded=%s levelRenderer=%s".formatted(
                mc.level == null ? null : mc.level.dimension().identifier(),
                mc.level == null ? -1 : mc.level.getChunkSource().getLoadedChunksCount(),
                mc.levelRenderer.hasRenderedAllSections(),
                mc.level != null && mc.player != null && mc.level.getChunkSource().getChunk(
                    mc.player.chunkPosition().x(), mc.player.chunkPosition().z(), ChunkStatus.FULL, false) != null,
                System.identityHashCode(mc.levelRenderer)
            ));
            throw new AssertionError("chunks not rendered (" + label + "): " + state, e);
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
