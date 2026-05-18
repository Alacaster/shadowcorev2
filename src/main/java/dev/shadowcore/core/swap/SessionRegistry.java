package dev.shadowcore.core.swap;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry of active {@link Session} objects, keyed by controller UUID.
 *
 * <p>Replaces {@code DualPlayerRegistry} from the 3.0 era. The API is the
 * same shape so Manager's call sites don't need to change: {@link
 * #byController(UUID)}, {@link #register}, {@link #unregister}, {@link
 * #all()}.</p>
 *
 * <p>New in 4.0: also maintains a reverse index for lookups by <em>parked
 * baseline</em> UUID. This is needed by {@code PearlShadowGuard} to answer
 * "is this ServerPlayer currently a parked baseline of some live shadow
 * session?" in O(1).</p>
 *
 * <p>Thread safety: insert/remove happen only on the main thread during
 * PlayerLifecycleListener's join/quit. Lookups can happen from any thread
 * (e.g., from AsyncPlayerPreLoginEvent), hence the concurrent map.</p>
 */
public final class SessionRegistry {
    private final Map<UUID, Session> byController = new ConcurrentHashMap<>();
    private final Map<UUID, Session> byParkedBaseline = new ConcurrentHashMap<>();

    public void register(final Session session) {
        Objects.requireNonNull(session);
        byController.put(session.controllerUuid(), session);
    }

    public Optional<Session> unregister(final UUID controllerUuid) {
        final Session s = byController.remove(controllerUuid);
        if (s != null && s.parkedBaseline() != null) {
            byParkedBaseline.remove(s.parkedBaseline().getUUID());
        }
        return Optional.ofNullable(s);
    }

    public Optional<Session> byController(final UUID controllerUuid) {
        if (controllerUuid == null) return Optional.empty();
        return Optional.ofNullable(byController.get(controllerUuid));
    }

    /**
     * Look up a session by the UUID of its currently-parked baseline.
     * Returns empty if no session has that baseline parked (either no
     * session, or the session is not currently shadowing).
     *
     * <p>Called by {@code PearlShadowGuard} on every pearl-cause teleport
     * event to determine whether the teleport target is a parked baseline
     * of a live shadow session.</p>
     */
    public Optional<Session> byParkedBaselineUuid(final UUID parkedUuid) {
        if (parkedUuid == null) return Optional.empty();
        return Optional.ofNullable(byParkedBaseline.get(parkedUuid));
    }

    /**
     * Called by IdentitySwap implementations when a shadow mount parks a
     * baseline. Establishes the reverse-index entry. Must be followed
     * eventually by {@link #clearParkedBaseline} on shadow end.
     */
    public void registerParkedBaseline(final Session session, final UUID parkedUuid) {
        byParkedBaseline.put(parkedUuid, session);
    }

    /** Reverse of {@link #registerParkedBaseline}, called on shadow end. */
    public void clearParkedBaseline(final UUID parkedUuid) {
        byParkedBaseline.remove(parkedUuid);
    }

    /** Read-only snapshot of all live sessions. Order is insertion order of the map. */
    public Collection<Session> all() {
        return Collections.unmodifiableCollection(byController.values());
    }

    public int size() { return byController.size(); }
}
