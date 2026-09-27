package qouteall.imm_ptl.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.gametest.mixin.ServerGamePacketListenerImplAccessor;

final class TestUtil {
    private TestUtil() {}

    /**
     * A player that joined through the player list, with the join teleport already acknowledged
     * (like a real client would), placed at {@code pos}.
     */
    static ServerPlayer makePlayerAt(GameTestHelper helper, Vec3 pos) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        acceptPendingTeleport(player);
        player.snapTo(pos.x, pos.y, pos.z, 0, 0);
        player.connection.resetPosition();
        return player;
    }

    static void acceptPendingTeleport(ServerPlayer player) {
        int id = ((ServerGamePacketListenerImplAccessor) player.connection).ipTest_getAwaitingTeleport();
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(
            id, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()
        ));
    }

    static void removePlayer(ServerPlayer player) {
        player.level().getServer().getPlayerList().remove(player);
    }

    /**
     * A 2x3 portal in the XY plane, facing +Z.
     */
    static Portal spawnPortal(
        ServerLevel level, Vec3 origin, ResourceKey<Level> destDim, Vec3 destination
    ) {
        return spawnPortal(level, origin, destDim, destination, 2, 3);
    }

    /**
     * A portal in the XY plane, facing +Z.
     */
    static Portal spawnPortal(
        ServerLevel level, Vec3 origin, ResourceKey<Level> destDim, Vec3 destination, double width, double height
    ) {
        Portal portal = Portal.ENTITY_TYPE.create(level, EntitySpawnReason.LOAD);
        if (portal == null) {
            throw new IllegalStateException("could not create portal entity");
        }
        portal.setOriginPos(origin);
        portal.setDestinationDimension(destDim);
        portal.setDestination(destination);
        portal.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), width, height);
        if (!level.addFreshEntity(portal)) {
            throw new IllegalStateException("could not add portal entity");
        }
        return portal;
    }

    static boolean near(Vec3 a, Vec3 b) {
        return a.distanceTo(b) < 0.01;
    }
}
