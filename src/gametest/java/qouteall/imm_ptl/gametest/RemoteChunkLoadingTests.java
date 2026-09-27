package qouteall.imm_ptl.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.chunk_loading.ChunkLoader;
import qouteall.imm_ptl.core.chunk_loading.ChunkVisibility;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.miscellaneous.ClientPerformanceMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How much of the destination dimension a player gets through a portal, compared with being in that dimension.
 * For a nether portal seen from 3 blocks away, samples over time: the chunk loaders the server computes for the
 * player, the nether chunks it has sent to the player, the nether chunks the client has, and (with Sodium) the
 * chunks Sodium considers ready to build. Then the same right after walking through the portal.
 * <p>
 * With Iris, runs with Complementary Shaders Reimagined if its zip is in the shaderpacks directory: the low frame
 * rate with a shader pack used to make the server load only 2 chunks around the portal destination.
 */
public class RemoteChunkLoadingTests implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteChunkLoadingTests.class);
    private static final int RENDER_DISTANCE = 12;
    private static final Vec3 NETHER_DESTINATION = new Vec3(0.5, 100.5, 0.5);
    private static final int MEASURE_RADIUS = 16;

    record Sample(String label, String perfServer, String perfClient, String loaders, int serverSent, int serverRadius,
                  int clientChunks, int clientRadius, int sodiumReady) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                "[imm_ptl chunks] %-22s perf(server/client)=%s/%s loaders=%s sent=%d (radius %d) client=%d (radius %d) sodiumReady=%d",
                label, perfServer, perfClient, loaders, serverSent, serverRadius, clientChunks, clientRadius, sodiumReady);
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(mc -> mc.options.renderDistance().set(RENDER_DISTANCE));
        boolean iris = FabricLoader.getInstance().isModLoaded("iris");
        List<String> packs = iris
            ? context.computeOnClient(mc -> IrisShaderPackTests.IrisControl.findPacks("ComplementaryReimagined"))
            : List.of();
        if (!packs.isEmpty()) {
            context.runOnClient(mc -> IrisShaderPackTests.IrisControl.setShaderPack(packs.getFirst()));
        }
        try {
            measure(context);
        }
        finally {
            if (!packs.isEmpty()) {
                context.runOnClient(mc -> IrisShaderPackTests.IrisControl.setShaderPack(null));
            }
        }
    }

    private void measure(ClientGameTestContext context) {
        List<Sample> samples = new ArrayList<>();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            int surfaceY = singleplayer.getServer().computeOnServer(
                server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0)
            );
            singleplayer.getServer().runCommand("gamemode creative @a");
            singleplayer.getServer().runCommand("tp @a 0.5 " + surfaceY + " 0.5 180 0");
            singleplayer.getConnection().waitForClientboundPackets();
            singleplayer.getServer().computeOnServer(server -> TestUtil.spawnPortal(
                server.overworld(), new Vec3(0.5, surfaceY + 1.5, -2.5), Level.NETHER, NETHER_DESTINATION
            ));

            for (int seconds : new int[]{2, 5, 10, 20, 40}) {
                int already = samples.isEmpty() ? 0 : Integer.parseInt(samples.getLast().label.replaceAll("\\D", ""));
                context.waitTicks((seconds - already) * 20);
                samples.add(sample(context, singleplayer, "overworld " + seconds + "s"));
                LOGGER.info("{}", samples.getLast());
            }

            // 3 blocks from the portal the server loads the player's render distance around the destination, up to
            // the configured cap; the client's frame rate (low with shader packs) must not shrink it
            int expectedRadius = Math.min(RENDER_DISTANCE, IPGlobal.indirectLoadingRadiusCap);
            for (Sample sample : samples.subList(1, samples.size())) {
                check(sample.loaders.contains("r" + expectedRadius),
                    "the portal chunk loader does not have radius " + expectedRadius + ": " + sample);
            }
            Sample loaded = samples.getLast();
            check(loaded.serverRadius == expectedRadius, "the server did not send the chunks of the portal loader: " + loaded);
            check(loaded.clientChunks == loaded.serverSent, "the client does not have the chunks sent to it: " + loaded);
            // Sodium only builds chunks whose neighbours are all present
            int innerChunks = (2 * expectedRadius - 1) * (2 * expectedRadius - 1);
            check(loaded.sodiumReady < 0 || loaded.sodiumReady >= innerChunks,
                "Sodium does not consider the received chunks ready: " + loaded);

            context.getInput().holdKeyFor(options -> options.keyUp, 40);
            singleplayer.getServer().waitFor(
                server -> onlyPlayer(server).level().dimension() == Level.NETHER, 200
            );
            context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.NETHER, 200);
            samples.add(sample(context, singleplayer, "nether +0s"));
            LOGGER.info("{}", samples.getLast());
            context.waitTicks(40);
            samples.add(sample(context, singleplayer, "nether +2s"));
            LOGGER.info("{}", samples.getLast());
            context.waitTicks(160);
            samples.add(sample(context, singleplayer, "nether +10s"));
            LOGGER.info("{}", samples.getLast());
        }
        StringBuilder report = new StringBuilder();
        for (Sample sample : samples) {
            report.append(sample).append('\n');
        }
        LOGGER.info("\n{}", report);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static ServerPlayer onlyPlayer(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static Sample sample(ClientGameTestContext context, TestSingleplayerContext singleplayer, String label) {
        ChunkPos center = ChunkPos.containing(net.minecraft.core.BlockPos.containing(NETHER_DESTINATION));
        String[] server = singleplayer.getServer().computeOnServer(s -> {
            ServerPlayer player = onlyPlayer(s);
            List<String> loaders = new ArrayList<>();
            ChunkVisibility.foreachBaseChunkLoaders(player, (ChunkLoader loader) -> {
                if (loader.dimension() == Level.NETHER) {
                    loaders.add("r" + loader.radius());
                }
            });
            int sent = 0, radius = 0;
            for (int dx = -MEASURE_RADIUS; dx <= MEASURE_RADIUS; dx++) {
                for (int dz = -MEASURE_RADIUS; dz <= MEASURE_RADIUS; dz++) {
                    if (ImmPtlChunkTracking.isPlayerWatchingChunk(player, Level.NETHER, center.x() + dx, center.z() + dz)) {
                        sent++;
                        radius = Math.max(radius, Math.max(Math.abs(dx), Math.abs(dz)));
                    }
                }
            }
            return new String[]{
                String.valueOf(ImmPtlChunkTracking.getPlayerInfo(player).performanceLevel),
                String.join(",", loaders), String.valueOf(sent), String.valueOf(radius)
            };
        });
        int[] client = context.computeOnClient(mc -> {
            ClientLevel nether = findClientWorld(Level.NETHER);
            if (nether == null) {
                return new int[]{0, 0, 0};
            }
            int count = 0, radius = 0;
            for (int dx = -MEASURE_RADIUS; dx <= MEASURE_RADIUS; dx++) {
                for (int dz = -MEASURE_RADIUS; dz <= MEASURE_RADIUS; dz++) {
                    if (nether.getChunkSource().getChunk(center.x() + dx, center.z() + dz, false) != null) {
                        count++;
                        radius = Math.max(radius, Math.max(Math.abs(dx), Math.abs(dz)));
                    }
                }
            }
            int sodiumReady = FabricLoader.getInstance().isModLoaded("sodium") ? SodiumProbe.readyChunks(nether) : -1;
            return new int[]{count, radius, sodiumReady};
        });
        String clientPerf = context.computeOnClient(mc -> ClientPerformanceMonitor.level + " fps "
            + ClientPerformanceMonitor.getAverageFps() + " freeMB " + ClientPerformanceMonitor.getAverageFreeMemoryMB());
        return new Sample(label, server[0], clientPerf, server[1], Integer.parseInt(server[2]),
            Integer.parseInt(server[3]), client[0], client[1], client[2]);
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
     * Only loaded with Sodium.
     */
    private static final class SodiumProbe {
        static int readyChunks(ClientLevel level) {
            return net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder.get(level).getReadyChunks().size();
        }
    }
}
