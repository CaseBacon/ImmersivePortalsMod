package qouteall.imm_ptl.core.teleportation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TeleportRequestCheckTest {

    private static TeleportRequestCheck.Facts valid(boolean mounted) {
        return new TeleportRequestCheck.Facts(
            true, false, true, true, mounted, 1.0, 0.5
        );
    }

    @Test
    public void acceptsConsistentRequest() {
        assertNull(TeleportRequestCheck.getRejectReason(valid(false)));
        assertNull(TeleportRequestCheck.getRejectReason(valid(true)));
    }

    /**
     * Regression test for the mounted-player bypass: before the 26.3 port, a player riding a
     * vehicle skipped every check, so the client could teleport through any portal from anywhere.
     */
    @Test
    public void mountedPlayerIsValidatedLikeAnyPlayer() {
        TeleportRequestCheck.Facts[] invalidMounted = {
            // wrong dimension
            new TeleportRequestCheck.Facts(true, false, false, true, true, 1.0, 0.5),
            // far away from the claimed position
            new TeleportRequestCheck.Facts(true, false, true, true, true, 1000.0, 0.5),
            // claimed position far away from the portal
            new TeleportRequestCheck.Facts(true, false, true, true, true, 1.0, 1000.0),
            // portal does not allow the player
            new TeleportRequestCheck.Facts(true, false, true, false, true, 1.0, 0.5),
            // a server teleport is pending
            new TeleportRequestCheck.Facts(true, true, true, true, true, 1.0, 0.5),
            // NaN position
            new TeleportRequestCheck.Facts(false, false, true, true, true, Double.NaN, Double.NaN),
        };
        for (TeleportRequestCheck.Facts facts : invalidMounted) {
            String mountedReason = TeleportRequestCheck.getRejectReason(facts);
            assertNotNull(mountedReason, facts.toString());

            TeleportRequestCheck.Facts unmounted = new TeleportRequestCheck.Facts(
                facts.requestPosFinite(), facts.hasAwaitingTeleport(), facts.playerInRequestDimension(),
                facts.portalCanTeleportPlayer(), false,
                facts.playerToRequestPosDistance(), facts.requestPosToPortalDistance()
            );
            assertEquals(TeleportRequestCheck.getRejectReason(unmounted), mountedReason);
        }
    }

    @Test
    public void rejectsNonFiniteDistances() {
        assertNotNull(TeleportRequestCheck.getRejectReason(
            new TeleportRequestCheck.Facts(true, false, true, true, false, Double.NaN, 0.5)
        ));
        assertNotNull(TeleportRequestCheck.getRejectReason(
            new TeleportRequestCheck.Facts(true, false, true, true, false, 1.0, Double.POSITIVE_INFINITY)
        ));
    }

    @Test
    public void distanceLimitsAreInclusive() {
        assertNull(TeleportRequestCheck.getRejectReason(new TeleportRequestCheck.Facts(
            true, false, true, true, false,
            TeleportRequestCheck.MAX_PLAYER_TO_REQUEST_POS_DISTANCE,
            TeleportRequestCheck.MAX_REQUEST_POS_TO_PORTAL_DISTANCE
        )));
        assertNotNull(TeleportRequestCheck.getRejectReason(new TeleportRequestCheck.Facts(
            true, false, true, true, false,
            Math.nextUp(TeleportRequestCheck.MAX_PLAYER_TO_REQUEST_POS_DISTANCE), 0
        )));
        assertNotNull(TeleportRequestCheck.getRejectReason(new TeleportRequestCheck.Facts(
            true, false, true, true, false,
            0, Math.nextUp(TeleportRequestCheck.MAX_REQUEST_POS_TO_PORTAL_DISTANCE)
        )));
    }
}
