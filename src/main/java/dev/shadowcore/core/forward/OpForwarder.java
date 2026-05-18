package dev.shadowcore.core.forward;

import dev.shadowcore.util.Diag;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Mirrors the controller's vanilla operator status onto the active
 * {@code ServerPlayer}.
 *
 * <p>Operator status in vanilla Minecraft is keyed on UUID. When a
 * controller is on a profile, their profile UUID is a separate entry from
 * their Mojang UUID. If the human is opped (their Mojang UUID is in
 * {@code ops.json}), this forwarder ensures the active {@code ServerPlayer}
 * — regardless of whose UUID it has — also has operator status applied
 * via {@link Player#setOp(boolean)}. The reverse also runs: if the human
 * is not opped, the active player is forced to non-op so that an old op
 * grant on a profile UUID does not leak permissions.</p>
 *
 * <p>This applies for every identity change, including shadow mounts —
 * an admin shadowing a non-admin target retains their admin powers
 * because op follows the human, not the active identity.</p>
 */
public final class OpForwarder {
    private final Logger log;

    public OpForwarder(final Logger log) {
        this.log = log;
    }

    /**
     * Apply the controller's op status to the active player.
     *
     * @param controllerMojangUuid the Mojang UUID of the human controller
     * @param activePlayer the Bukkit Player object currently driven by the
     *                     controller's socket — may have a profile UUID,
     *                     a shadow target's UUID, or the Mojang UUID itself
     */
    public void apply(final UUID controllerMojangUuid, final Player activePlayer) {
        if (controllerMojangUuid == null || activePlayer == null) return;
        final OfflinePlayer human = Bukkit.getOfflinePlayer(controllerMojangUuid);
        final boolean shouldBeOp = human.isOp();
        final boolean isOp = activePlayer.isOp();
        if (shouldBeOp == isOp) {
            Diag.trace(log, "fwd",
                "op: " + activePlayer.getName() + " op=" + isOp + " (already correct)");
            return;
        }
        Diag.info(log, "fwd",
            "op: " + activePlayer.getName() + " (uuid=" + activePlayer.getUniqueId()
            + ") " + isOp + " -> " + shouldBeOp + " (controller=" + controllerMojangUuid + ")");
        activePlayer.setOp(shouldBeOp);
    }
}
