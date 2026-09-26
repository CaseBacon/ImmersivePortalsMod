package qouteall.imm_ptl.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

import java.util.UUID;

/**
 * Server-side handling of the client portal teleport request ({@code imm_ptl:teleport}).
 * The requests are fed directly into {@link ServerTeleportationManager#onPlayerTeleportedInClient},
 * which is what the packet handler calls.
 */
public class TeleportRequestTests {

    private static ServerTeleportationManager manager(GameTestHelper helper) {
        return ServerTeleportationManager.of(helper.getLevel().getServer());
    }

    private static Entity mount(GameTestHelper helper, ServerPlayer player) {
        ServerLevel level = helper.getLevel();
        Entity minecart = EntityTypes.MINECART.create(level, EntitySpawnReason.LOAD);
        helper.assertTrue(minecart != null, "could not create minecart");
        minecart.snapTo(player.getX(), player.getY(), player.getZ(), 0, 0);
        helper.assertTrue(level.addFreshEntity(minecart), "could not add minecart");
        helper.assertTrue(player.startRiding(minecart, true, true), "player could not ride the minecart");
        // mounting sends a position packet; a real client acknowledges it right away
        TestUtil.acceptPendingTeleport(player);
        return minecart;
    }

    @GameTest
    public void acceptsPlayerNextToPortal(GameTestHelper helper) {
        Vec3 playerPos = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        ServerPlayer player = TestUtil.makePlayerAt(helper, playerPos);
        Portal portal = TestUtil.spawnPortal(
            helper.getLevel(), playerPos.add(0, 1, 0.5), Level.OVERWORLD, playerPos.add(4, 1, 0.5)
        );
        Vec3 eyePos = McHelper.getEyePos(player);
        Vec3 expectedFeet = portal.transformPoint(eyePos).subtract(McHelper.getEyeOffset(player));

        String reason = manager(helper).onPlayerTeleportedInClient(player, Level.OVERWORLD, eyePos, portal.getUUID());
        
        helper.assertTrue(reason == null, "rejected: " + reason);
        helper.assertTrue(
            TestUtil.near(player.position(), expectedFeet),
            "player was not teleported: " + player.position() + " expected " + expectedFeet
        );
        TestUtil.removePlayer(player);
        portal.discard();
        helper.succeed();
    }

    /**
     * Regression test for the mounted-player bypass (plan amendment 4): a riding player used to
     * skip every check, so a client could use any portal of the dimension from anywhere.
     */
    @GameTest
    public void rejectsMountedPlayerFarFromPortal(GameTestHelper helper) {
        Vec3 playerPos = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        ServerPlayer player = TestUtil.makePlayerAt(helper, playerPos);
        Entity vehicle = mount(helper, player);
        Vec3 positionBefore = player.position();

        // the portal is 40 blocks away from the player
        Vec3 portalOrigin = playerPos.add(40, 1, 0);
        Portal portal = TestUtil.spawnPortal(
            helper.getLevel(), portalOrigin, Level.OVERWORLD, portalOrigin.add(0, 0, 8)
        );

        // the client claims that its eye was right at the portal
        String reason = manager(helper).onPlayerTeleportedInClient(player, Level.OVERWORLD, portalOrigin, portal.getUUID());
        
        helper.assertTrue(
            "player is too far from the posBefore in packet".equals(reason),
            "unexpected result: " + reason
        );
        helper.assertTrue(
            player.position().distanceTo(positionBefore) < 1,
            "mounted player was teleported: " + positionBefore + " -> " + player.position()
        );
        helper.assertTrue(player.getVehicle() == vehicle, "player no longer rides the vehicle");

        TestUtil.removePlayer(player);
        vehicle.discard();
        portal.discard();
        helper.succeed();
    }

    @GameTest
    public void acceptsMountedPlayerNextToPortal(GameTestHelper helper) {
        Vec3 playerPos = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        ServerPlayer player = TestUtil.makePlayerAt(helper, playerPos);
        Entity vehicle = mount(helper, player);
        Portal portal = TestUtil.spawnPortal(
            helper.getLevel(), player.position().add(0, 1, 0.5), Level.OVERWORLD, player.position().add(4, 1, 0.5)
        );
        Vec3 positionBefore = player.position();
        Vec3 eyePos = McHelper.getEyePos(player);

        String reason = manager(helper).onPlayerTeleportedInClient(player, Level.OVERWORLD, eyePos, portal.getUUID());
        
        helper.assertTrue(reason == null, "rejected: " + reason);
        helper.assertTrue(
            player.position().distanceTo(positionBefore) > 3,
            "mounted player next to the portal was not teleported: " + player.position()
        );

        TestUtil.removePlayer(player);
        vehicle.discard();
        portal.discard();
        helper.succeed();
    }

    @GameTest
    public void rejectsWrongDimension(GameTestHelper helper) {
        Vec3 playerPos = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        ServerPlayer player = TestUtil.makePlayerAt(helper, playerPos);
        Portal portal = TestUtil.spawnPortal(
            helper.getLevel(), playerPos.add(0, 1, 0.5), Level.OVERWORLD, playerPos.add(4, 1, 0.5)
        );

        // the portal is not in the nether, so it is not found there
        String reason = manager(helper).onPlayerTeleportedInClient(player, Level.NETHER, McHelper.getEyePos(player), portal.getUUID());
        
        helper.assertTrue("portal not found".equals(reason), "unexpected result: " + reason);
        helper.assertTrue(TestUtil.near(player.position(), playerPos), "player moved: " + player.position());
        helper.assertTrue(player.level().dimension() == Level.OVERWORLD, "player changed dimension");
        TestUtil.removePlayer(player);
        portal.discard();
        helper.succeed();
    }

    @GameTest
    public void rejectsUnknownPortal(GameTestHelper helper) {
        Vec3 playerPos = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        ServerPlayer player = TestUtil.makePlayerAt(helper, playerPos);

        String reason = manager(helper).onPlayerTeleportedInClient(player, Level.OVERWORLD, McHelper.getEyePos(player), UUID.randomUUID());
        
        helper.assertTrue("portal not found".equals(reason), "unexpected result: " + reason);
        helper.assertTrue(TestUtil.near(player.position(), playerPos), "player moved: " + player.position());
        TestUtil.removePlayer(player);
        helper.succeed();
    }

    @GameTest
    public void rejectsNonFinitePosition(GameTestHelper helper) {
        Vec3 playerPos = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        ServerPlayer player = TestUtil.makePlayerAt(helper, playerPos);
        Portal portal = TestUtil.spawnPortal(
            helper.getLevel(), playerPos.add(0, 1, 0.5), Level.OVERWORLD, playerPos.add(4, 1, 0.5)
        );

        String reason = manager(helper).onPlayerTeleportedInClient(
            player, Level.OVERWORLD, new Vec3(Double.NaN, playerPos.y, Double.POSITIVE_INFINITY), portal.getUUID()
        );
        
        helper.assertTrue("position in packet is not finite".equals(reason), "unexpected result: " + reason);
        helper.assertTrue(TestUtil.near(player.position(), playerPos), "player moved: " + player.position());
        TestUtil.removePlayer(player);
        portal.discard();
        helper.succeed();
    }

    @GameTest
    public void rejectsWhileServerTeleportIsPending(GameTestHelper helper) {
        Vec3 playerPos = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        ServerPlayer player = TestUtil.makePlayerAt(helper, playerPos);
        Portal portal = TestUtil.spawnPortal(
            helper.getLevel(), playerPos.add(0, 1, 0.5), Level.OVERWORLD, playerPos.add(4, 1, 0.5)
        );
        // the server moves the player and waits for the client to acknowledge it
        player.connection.teleport(playerPos.x, playerPos.y, playerPos.z, 0, 0);
        
        String reason = manager(helper).onPlayerTeleportedInClient(player, Level.OVERWORLD, McHelper.getEyePos(player), portal.getUUID());
        
        helper.assertTrue("has awaiting teleport".equals(reason), "unexpected result: " + reason);
        helper.assertTrue(TestUtil.near(player.position(), playerPos), "player moved: " + player.position());
        TestUtil.removePlayer(player);
        portal.discard();
        helper.succeed();
    }

    @GameTest
    public void teleportsAcrossDimensions(GameTestHelper helper) {
        Vec3 playerPos = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        ServerPlayer player = TestUtil.makePlayerAt(helper, playerPos);
        Portal portal = TestUtil.spawnPortal(
            helper.getLevel(), playerPos.add(0, 1, 0.5), Level.NETHER, new Vec3(0.5, 70, 0.5)
        );
        Vec3 eyePos = McHelper.getEyePos(player);
        Vec3 expectedFeet = portal.transformPoint(eyePos).subtract(McHelper.getEyeOffset(player));

        String reason = manager(helper).onPlayerTeleportedInClient(player, Level.OVERWORLD, eyePos, portal.getUUID());
        
        helper.assertTrue(reason == null, "rejected: " + reason);
        helper.assertTrue(player.level().dimension() == Level.NETHER, "player is in " + player.level().dimension());
        helper.assertTrue(
            TestUtil.near(player.position(), expectedFeet),
            "player position " + player.position() + " expected " + expectedFeet
        );
        helper.assertTrue(
            helper.getLevel().getServer().getLevel(Level.NETHER).getEntity(player.getUUID()) == player,
            "player is not registered in the nether level"
        );
        helper.assertTrue(
            helper.getLevel().getEntity(player.getUUID()) == null,
            "player is still registered in the overworld level"
        );

        TestUtil.removePlayer(player);
        portal.discard();
        helper.succeed();
    }
    
    @GameTest
    public void teleportsMountedPlayerAcrossDimensionsWithVehicle(GameTestHelper helper) {
        Vec3 playerPos = helper.absoluteVec(new Vec3(1.5, 1, 1.5));
        ServerPlayer player = TestUtil.makePlayerAt(helper, playerPos);
        Entity vehicle = mount(helper, player);
        Portal portal = TestUtil.spawnPortal(
            helper.getLevel(), player.position().add(0, 1, 0.5), Level.NETHER, new Vec3(0.5, 70, 0.5)
        );
        
        String reason = manager(helper).onPlayerTeleportedInClient(
            player, Level.OVERWORLD, McHelper.getEyePos(player), portal.getUUID()
        );
        
        helper.assertTrue(reason == null, "rejected: " + reason);
        helper.assertTrue(player.level().dimension() == Level.NETHER, "player is in " + player.level().dimension());
        Entity newVehicle = player.getVehicle();
        helper.assertTrue(newVehicle != null, "player lost the vehicle");
        helper.assertTrue(newVehicle.level().dimension() == Level.NETHER, "vehicle is in " + newVehicle.level().dimension());
        helper.assertTrue(newVehicle.position().distanceTo(player.position()) < 2, "vehicle is not at the player");
        
        TestUtil.removePlayer(player);
        newVehicle.discard();
        if (vehicle != newVehicle) {
            vehicle.discard();
        }
        portal.discard();
        helper.succeed();
    }
}
