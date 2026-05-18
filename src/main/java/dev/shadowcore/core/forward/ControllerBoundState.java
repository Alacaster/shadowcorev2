package dev.shadowcore.core.forward;

import dev.shadowcore.core.swap.Session;
import dev.shadowcore.model.MountedIdentity;
import dev.shadowcore.util.Diag;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Applies controller-bound state (op, permissions, optionally skin) to
 * whichever {@code ServerPlayer} is currently driving a controller's
 * socket.
 *
 * <p>Called after every successful identity change: initial join, profile
 * switch, shadow mount, shadow end, conflict shell entry/exit. Each
 * sub-forwarder runs independently — failure of one (e.g., LuckPerms not
 * present, skin fetch failed) does not stop the others.</p>
 *
 * <h2>Forwarding rules</h2>
 *
 * <table>
 *   <tr><th>Property</th><th>Profile mount</th><th>Shadow mount</th></tr>
 *   <tr><td>Op status</td><td>Forward</td><td>Forward</td></tr>
 *   <tr><td>Permissions</td><td>Forward</td><td>Forward</td></tr>
 *   <tr><td>Skin</td><td>Forward</td><td>Skip (target's own)</td></tr>
 * </table>
 *
 * <p>Op and permissions always follow the human, so admin powers persist
 * across shadow sessions. Skin follows only for profiles — shadow's whole
 * point is to be visually indistinguishable from the target.</p>
 */
public final class ControllerBoundState {
    private final Logger log;
    private final OpForwarder opFwd;
    private final PermissionForwarder permFwd;
    private final SkinForwarder skinFwd;

    public ControllerBoundState(final Logger log,
                                final OpForwarder opFwd,
                                final PermissionForwarder permFwd,
                                final SkinForwarder skinFwd) {
        this.log = log;
        this.opFwd = opFwd;
        this.permFwd = permFwd;
        this.skinFwd = skinFwd;
    }

    /**
     * Apply forwarding for the given session's current identity.
     *
     * @param session the controller's session, with {@link Session#current()}
     *                pointing at the active ServerPlayer.
     */
    public void applyForCurrentIdentity(final Session session) {
        if (session == null || session.current() == null) {
            Diag.trace(log, "fwd", "applyForCurrentIdentity: null session/current, skipping");
            return;
        }
        final UUID controllerUuid = session.controllerUuid();
        final UUID activeUuid = session.current().getUUID();
        final Player activePlayer = Bukkit.getPlayer(activeUuid);
        if (activePlayer == null || !activePlayer.isOnline()) {
            Diag.warn(log, "fwd",
                "applyForCurrentIdentity: active Player " + activeUuid
                + " is not online, cannot apply forwarding");
            return;
        }
        final MountedIdentity identity = session.currentIdentity();
        final MountedIdentity.Kind kind = identity == null ? null : identity.kind();
        Diag.info(log, "fwd",
            "applyForCurrentIdentity: controller=" + controllerUuid
            + " active=" + activeUuid + " kind=" + kind);

        // Op + permissions always forward.
        opFwd.apply(controllerUuid, activePlayer);
        permFwd.apply(controllerUuid, activePlayer);

        // Skin forwards only for profile mounts. For shadow, the target's
        // own skin is the right one — skipping skinFwd here means vanilla's
        // PlayerProfile (constructed from the target's GameProfile) stays
        // in effect.
        if (kind == MountedIdentity.Kind.MAIN || kind == MountedIdentity.Kind.LOCAL) {
            skinFwd.apply(controllerUuid, activePlayer);
        } else {
            Diag.trace(log, "fwd",
                "skin: kind=" + kind + " — keeping target's skin, no forward");
        }
    }
}
