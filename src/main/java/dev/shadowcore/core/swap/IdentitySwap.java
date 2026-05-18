package dev.shadowcore.core.swap;

import dev.shadowcore.model.MountDisposition;
import dev.shadowcore.model.MountedIdentity;
import org.bukkit.GameMode;

/**
 * The core identity-swap operation — the 4.0 replacement for
 * {@code MountKernel}.
 *
 * <h2>Design premise</h2>
 * <p>There is ONE {@code ServerPlayer} per active identity, with ONE
 * {@code Connection} driving it. When the controller switches identities,
 * the old identity's ServerPlayer is saved and removed, and the new
 * identity's ServerPlayer is constructed and wired to the same channel
 * via a protocol-level phase reset. No shared-channel mirroring, no
 * packet classifier, no doll.</p>
 *
 * <p>For shadow mode specifically, the old baseline is not removed but
 * <em>parked</em> in a sealed bedrock chamber with a detached
 * {@link dev.shadowcore.core.nms.NoopConnection}, keeping its UUID live
 * in the world (so ender pearls, pets, allays continue to track it).
 * See the 4.0 spec for the parking chamber details.</p>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@code ReconfigureIdentitySwap} — direct-connect Paper path. Uses
 *       {@code PlayerGameConnection#reenterConfiguration}. Fast, uses the
 *       same socket.</li>
 *   <li>{@code VelocityIdentitySwap} — Velocity-proxied path. Uses cookie
 *       storage plus {@code ClientboundTransferPacket} to force a fresh
 *       TCP reconnect through the proxy; login hook reads the cookie and
 *       places the client on the correct ServerPlayer.</li>
 * </ul>
 *
 * <p>Selection between implementations happens at session start based on
 * whether Velocity is detected upstream.</p>
 *
 * <h2>Thread model</h2>
 * All public methods are main-thread only. Implementations may internally
 * schedule async work (e.g., waiting for a configuration-phase ack), but
 * the caller interacts only on the server thread.
 */
public interface IdentitySwap {

    /**
     * Mount the given identity onto the session. Fails (returns false) if
     * the session already has a non-null current identity — callers must
     * use {@link #swap} instead.
     *
     * <p>This is only used at session bootstrap (first placement after
     * initial login). For every subsequent mount change, the session
     * already has a current identity and callers route through {@link
     * #swap}.</p>
     */
    boolean mount(Session session, MountedIdentity identity);

    /**
     * Swap the session from its current identity to {@code next}, applying
     * {@code dispositionForOld} to the outgoing identity.
     *
     * <p>COMMIT disposition saves the outgoing identity via vanilla
     * PlayerList.save. DISCARD restores the outgoing identity's pre-mount
     * .dat via MountBackup (requires that MountBackup captured the state
     * before the previous mount).</p>
     *
     * <p>If the new identity is a SHADOW, the current body becomes the
     * parked baseline (saved and parked in a chamber, not removed). If
     * the old identity is a SHADOW and the new is a baseline (MAIN or
     * LOCAL), the shadow target is removed and the parked baseline is
     * unparked and reattached to the channel.</p>
     */
    boolean swap(Session session, MountedIdentity next, MountDisposition dispositionForOld);

    /** Save the session's current ServerPlayer state via vanilla save path. */
    void persist(Session session);

    /**
     * Remove the session's current identity and leave the session in an
     * empty state. Used at controller quit and during conflict-shell entry.
     */
    void unmount(Session session, MountDisposition disposition);

    /**
     * Reload the session's current identity from its on-disk .dat,
     * discarding any in-memory changes since the last save. Implements
     * spec §11 "discard" for normal mounted states.
     *
     * @return true on success.
     */
    boolean reload(Session session);

    /**
     * Enter the conflict spectator shell state (spec §13). Called after a
     * forced shadow end when the baseline restore is deferred. The session's
     * current identity becomes a synthetic shell ServerPlayer; the real
     * baseline remains parked (the shadow target is already gone).
     */
    void enterConflictShell(Session session);

    /**
     * Resolve the deferred restore: exit the conflict shell by swapping
     * the channel from the synthetic shell back to the parked baseline,
     * remove the shell, unpark the baseline.
     *
     * @param baselineAsMount the baseline identity to restore (main or local).
     * @param resetPosition if true, teleport the baseline to its pre-shadow
     *                      coordinates; if false, preserve the shell's
     *                      current location.
     * @param requestedMode the gamemode the controller requested when
     *                      leaving spectator (triggering the resolve).
     * @return true on success.
     */
    boolean resolveDeferredRestore(Session session, MountedIdentity baselineAsMount,
                                   boolean resetPosition, GameMode requestedMode);
}
