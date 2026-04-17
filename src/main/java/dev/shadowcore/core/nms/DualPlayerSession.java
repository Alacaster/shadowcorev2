package dev.shadowcore.core.nms;

import dev.shadowcore.model.MountedIdentity;
import dev.shadowcore.model.ProfileIdentity;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;

/**
 * The per-controller runtime binding that ties the controller-side
 * {@link ServerPlayer} (and its real Netty {@link Connection}) to the
 * currently mounted-side {@link ServerPlayer} (the "doll").
 *
 * <p>This object is created at the moment vanilla login places the
 * controller's {@code ServerPlayer} in the world and lives until the
 * controller disconnects. The mounted-side pointer is replaced every time
 * the controller changes mount.</p>
 *
 * <p>Invariants:</p>
 * <ul>
 *   <li>{@link #controller()} is non-null for the lifetime of this session.</li>
 *   <li>{@link #realConnection()} is the controller's original Netty connection.</li>
 *   <li>{@link #mounted()} may be null during transitional instants while a
 *       mount is being swapped; under lock it is only observed non-null.</li>
 *   <li>During a mount swap the old mounted-side is removed from the world
 *       <em>before</em> the new one is placed.</li>
 * </ul>
 *
 * <p>This class holds no gameplay state. All gameplay state is on the
 * {@link ServerPlayer} instances themselves — inventory, location, health,
 * etc. — and therefore saved/loaded by vanilla paths keyed on the mounted
 * UUID.</p>
 */
public final class DualPlayerSession {
    private final UUID controllerUuid;
    private final ServerPlayer controller;
    private final Connection realConnection;

    // Mutable — swapped on every mount transition. Accessed under the
    // kernel's main-thread ordering; mutation is only allowed from the main
    // thread during EventEngine drain.
    private ServerPlayer mounted;
    private MountedIdentity mountedIdentity;
    private ProfileIdentity baseline;
    // Is this session currently in the conflict-spectator shell (§7)?
    // The mounted-side is absent during that state; we park the controller
    // in spectator in its own body.
    private boolean conflictShell;

    public DualPlayerSession(final UUID controllerUuid, final ServerPlayer controller,
                             final Connection realConnection) {
        this.controllerUuid = Objects.requireNonNull(controllerUuid);
        this.controller = Objects.requireNonNull(controller);
        this.realConnection = Objects.requireNonNull(realConnection);
    }

    public UUID controllerUuid() { return controllerUuid; }
    public ServerPlayer controller() { return controller; }
    public Connection realConnection() { return realConnection; }

    /**
     * The currently mounted doll, or {@code null} if the session is in the
     * conflict-spectator shell state. For a freshly joined controller that
     * has not yet been reconciled, this should transiently be {@code null}.
     */
    public ServerPlayer mounted() { return mounted; }

    public MountedIdentity mountedIdentity() { return mountedIdentity; }
    public ProfileIdentity baseline() { return baseline; }
    public boolean conflictShell() { return conflictShell; }

    /** Main-thread only. */
    public void setMounted(final ServerPlayer mounted, final MountedIdentity identity, final ProfileIdentity baseline) {
        this.mounted = mounted;
        this.mountedIdentity = identity;
        this.baseline = baseline;
        this.conflictShell = false;
    }

    /** Main-thread only. */
    public void enterConflictShell() {
        this.mounted = null;
        this.mountedIdentity = null;
        // baseline is retained so the deferred restore knows where to go.
        this.conflictShell = true;
    }

    /** Main-thread only. */
    public void clearConflictShell() {
        this.conflictShell = false;
    }

    /**
     * True when the controller-side is the same NMS instance as the
     * mounted-side — that is, when the controller is in its own main-account
     * body with no mount swap active. This is a fast path for presentation.
     */
    public boolean isSelfMounted() {
        return mounted != null && mounted == controller;
    }
}
