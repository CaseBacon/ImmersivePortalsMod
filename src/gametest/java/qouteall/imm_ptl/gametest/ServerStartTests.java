package qouteall.imm_ptl.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.q_misc_util.dimension.DimensionIntId;

import java.util.List;

/**
 * If this runs at all, the server started with Immersive Portals, DimLib and all of their mixins.
 */
public class ServerStartTests {
    @GameTest
    public void modsAreLoaded(GameTestHelper helper) {
        helper.assertTrue(FabricLoader.getInstance().isModLoaded("immersive_portals"), "immersive_portals is not loaded");
        helper.assertTrue(FabricLoader.getInstance().isModLoaded("dimlib"), "dimlib is not loaded");
        helper.succeed();
    }

    @GameTest
    public void dimensionsAreKnown(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        for (ResourceKey<Level> dim : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            ServerLevel level = server.getLevel(dim);
            helper.assertTrue(level != null, "missing level " + dim.identifier());
            // the integer id map is used by the networking of the mod
            helper.assertTrue(
                DimensionIntId.getServerMap(server).fromIntegerIdNullable(
                    DimensionIntId.getServerMap(server).toIntegerId(dim)
                ) == dim,
                "dimension id map does not round trip " + dim.identifier()
            );
            // saved data type registration works for every level
            helper.assertTrue(GlobalPortalStorage.get(level) != null, "no global portal storage in " + dim.identifier());
        }
        helper.succeed();
    }
}
