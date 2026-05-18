package dev.shadowcore.core.swap;

import dev.shadowcore.model.MountedIdentity;
import dev.shadowcore.model.ProfileIdentity;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;

/**
 * The per-controller runtime binding for ShadowCore 4.0.
 *
 * <p>Replaces {@code DualPlayerSession} from the 3.0 doll era. The
 * fundamental difference: 3.0 had two {@link ServerPlayer} references
 * (a "controller" body and a "mounted" doll) sharing one {@link
 * Connection} via a reflective mirror. 4.0 has ONE ServerPlayer per
 * active identity: when the controller swaps identity (profile switch
 * or shadow mount), the ServerPlayer is replaced wholesale and the
 * channel's game listener is rewired to the new one.</p>
 *
 * <p>During a shadow session, a second {@link ServerPlayer} may exist
 * for the session — the <em>parked baseline</em>. The parked baseline
 * is stored in {@link #parkedBaseline()} and its connection is a
 * detached {@link dev.shadowcore.core.nms.NoopConnection}, not the
 * controller's real socket. There is never a ServerPlayer sharing the
 * controller's real channel with the current {@link #current()}.</p>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>{@link #realConnection()} is the controller's original Netty
 *       connection, stable for the lifetime of the session.</li>
 *   <li>{@link #current()} is the ServerPlayer currently driven by
 *       {@code realConnection}. Never null while the session is
 *       live in the world.</li>
 *   <li>{@link #parkedBaseline()} is non-null iff the session is in
 *       {@link dev.shadowcore.model.MountedIdentity.Kind#SHADOW}
 *       mode. During shadow, {@code current()} is the shadow target
 *       body and {@code parkedBaseline()} is the controller's real
 *       baseline identity parked in a sealed chamber.</li>
 *   <li>{@code conflictShell} is true when the session is in the
 *       post-forced-shadow-end deferred-restore state. During shell,
 *       {@code current()} is a synthetic throwaway ServerPlayer,
 *       and {@code parkedBaseline()} remains the real baseline (still
 *       parked), waiting for the deferred restore trigger.</li>
 * </ul>
 *
 * <p>This class holds no gameplay state. All gameplay state is on the
 * {@link ServerPlayer} instances themselves — inventory, location,
 * health — and therefore saved and loaded by vanilla paths keyed on
 * the mounted identity's UUID.</p>
 *
 * <p>All mutation happens on the main thread during the EventEngine
 * drain.</p>
 */
public final class Session {
    private final UUID controllerUuid;
    private final Connection realConnection;

    // The currently-active ServerPlayer — the one driven by realConnection.
    // Swapped wholesale on every mount transition.
    private ServerPlayer current;
    private MountedIdentity currentIdentity;
    private ProfileIdentity baseline;

    // The parked baseline during shadow mode. Null when not shadowing.
    // Its connection is a NoopConnection, not realConnection.
    private ServerPlayer parkedBaseline;

    // Post-forced-shadow-end deferred-restore state. Synthetic shell
    // ServerPlayer is currently active; real baseline remains parked.
    private boolean conflictShell;

    public Session(final UUID controllerUuid, final ServerPlayer initialCurrent,
                   final Connection realConnection) {
        this.controllerUuid = Objects.requireNonNull(controllerUuid);
        this.current = Objects.requireNonNull(initialCurrent);
        this.realConnection = Objects.requireNonNull(realConnection);
    }

    public UUID controllerUuid() { return controllerUuid; }
    public Connection realConnection() { return realConnection; }

    public ServerPlayer current() { return current; }
    public MountedIdentity currentIdentity() { return currentIdentity; }
    public ProfileIdentity baseline() { return baseline; }
    public ServerPlayer parkedBaseline() { return parkedBaseline; }
    public boolean conflictShell() { return conflictShell; }

    /** Main-thread only. Called by IdentitySwap after a successful swap. */
    public void setCurrent(final ServerPlayer current, final MountedIdentity identity,
                           final ProfileIdentity baseline) {
        this.current = current;
        this.currentIdentity = identity;
        this.baseline = baseline;
    }

    /** Main-thread only. Called when entering shadow mode to record the parked baseline. */
    public void setParkedBaseline(final ServerPlayer parked) {
        this.parkedBaseline = parked;
    }

    /** Main-thread only. Called when leaving shadow mode (baseline is being unparked). */
    public void clearParkedBaseline() {
        this.parkedBaseline = null;
    }

    /** Main-thread only. Enter the conflict spectator shell state. */
    public void enterConflictShell() {
        this.conflictShell = true;
    }

    /** Main-thread only. Leave the shell state (the deferred restore resolved). */
    public void clearConflictShell() {
        this.conflictShell = false;
    }

    // Compatibility shims — the Manager was written against DualPlayerSession
    // which had methods named mounted()/setMounted(). To minimize churn in
    // Manager during the rewire, we expose the same names. These will be
    // renamed to the 4.0 terminology (current/setCurrent) in a follow-up
    // pass after Manager is fully audited against the new flow.
    //
    // Spec §6 defines "mounted identity" as "the identity the world must
    // treat as the real logged-in actor" — in 4.0 terms, that's the
    // `current` ServerPlayer. So `mounted() == current()` is exactly the
    // right mapping.

    /** @deprecated Use {@link #current()}. Kept for Manager compatibility during rewire. */
    @Deprecated
    public ServerPlayer mounted() { return current; }

    /** @deprecated Use {@link #currentIdentity()}. Kept for Manager compatibility during rewire. */
    @Deprecated
    public MountedIdentity mountedIdentity() { return currentIdentity; }

    /** @deprecated Use {@link #setCurrent}. Kept for Manager compatibility during rewire. */
    @Deprecated
    public void setMounted(final ServerPlayer mounted, final MountedIdentity identity,
                           final ProfileIdentity baseline) {
        setCurrent(mounted, identity, baseline);
    }

    /**
     * During 3.0 this meant "is the controller's ServerPlayer the same
     * instance as the mounted ServerPlayer" (fast path for self-mount
     * presentation). In 4.0 there is no separate controller body —
     * {@code current()} is always the one that answers to the socket. So
     * self-mounted is effectively always true whenever we're on a MAIN or
     * LOCAL mount (no shadow, no shell). Kept as a method for Manager's
     * existing call sites.
     */
    public boolean isSelfMounted() {
        return parkedBaseline == null && !conflictShell;
    }

    /** @deprecated In 3.0 terminology; same as {@code current()} in 4.0. */
    @Deprecated
    public ServerPlayer controller() { return current; }
}
