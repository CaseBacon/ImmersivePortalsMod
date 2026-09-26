package qouteall.imm_ptl.core.teleportation;

import org.jetbrains.annotations.Nullable;

/**
 * Server-side validation of a client portal teleport request ({@code imm_ptl:teleport}).
 * <p>
 * The client decides when it crosses a portal, but the server stays authoritative:
 * a request is only accepted if it is consistent with the server's view of the player.
 * Mounted players are validated with the same rules (their vehicle is moved together with them).
 * <p>
 * Kept free of game objects so that the rules can be unit tested.
 */
public final class TeleportRequestCheck {
    /**
     * Max distance between the player's server-side position and the position in the request.
     */
    public static final double MAX_PLAYER_TO_REQUEST_POS_DISTANCE = 16;

    /**
     * Max distance between the position in the request and the portal.
     */
    public static final double MAX_REQUEST_POS_TO_PORTAL_DISTANCE = 20;

    private TeleportRequestCheck() {}

    /**
     * @param requestPosFinite                all coordinates of the position in the request are finite
     * @param hasAwaitingTeleport             the server is waiting for the client to accept a server teleport
     * @param playerInRequestDimension        the player is in the dimension named by the request
     * @param portalCanTeleportPlayer         {@code Portal.canTeleportEntity(player)}
     * @param mounted                         the player rides a vehicle. This never relaxes any rule
     *                                        (before the 26.3 port, mounted players skipped all checks).
     * @param playerToRequestPosDistance      distance from the player's server position to the request position
     * @param requestPosToPortalDistance      distance from the request position to the nearest point of the portal
     */
    public record Facts(
        boolean requestPosFinite,
        boolean hasAwaitingTeleport,
        boolean playerInRequestDimension,
        boolean portalCanTeleportPlayer,
        boolean mounted,
        double playerToRequestPosDistance,
        double requestPosToPortalDistance
    ) {}

    /**
     * @return null if the request is valid, otherwise the reason for rejecting it
     */
    public static @Nullable String getRejectReason(Facts facts) {
        if (!facts.requestPosFinite()) {
            return "position in packet is not finite";
        }

        // cannot teleport if having awaiting teleport
        if (facts.hasAwaitingTeleport()) {
            return "has awaiting teleport";
        }

        if (!facts.playerInRequestDimension()) {
            return "player is not in the dimensionBefore in packet";
        }

        if (!facts.portalCanTeleportPlayer()) {
            return "portal cannot teleport player";
        }

        // written as !(a <= b) so that NaN is rejected
        if (!(facts.playerToRequestPosDistance() <= MAX_PLAYER_TO_REQUEST_POS_DISTANCE)) {
            return "player is too far from the posBefore in packet";
        }

        if (!(facts.requestPosToPortalDistance() <= MAX_REQUEST_POS_TO_PORTAL_DISTANCE)) {
            return "posBefore is too far from portal";
        }

        return null;
    }
}
