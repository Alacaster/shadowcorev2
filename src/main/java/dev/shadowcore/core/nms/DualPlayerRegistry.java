package dev.shadowcore.core.nms;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of live {@link DualPlayerSession} objects, keyed by
 * controller UUID.
 *
 * <p>The registry is the single source of truth for "is controller X
 * currently connected, and what is their mount?". It is read from many
 * surfaces (presentation rewriters on the Netty pipeline thread, chunk
 * visibility filters, tab-list handlers) and written on the main thread
 * during EventEngine drain when mounts transition.</p>
 */
public final class DualPlayerRegistry {
    private final Map<UUID, DualPlayerSession> byController = new ConcurrentHashMap<>();

    public void register(final DualPlayerSession session) {
        byController.put(session.controllerUuid(), session);
    }

    public Optional<DualPlayerSession> byController(final UUID controllerUuid) {
        return Optional.ofNullable(byController.get(controllerUuid));
    }

    /**
     * Find the session whose <em>mounted</em> identity currently matches the
     * given UUID. This is the right lookup when a vanilla or third-party code
     * path hands you the mounted player's UUID (e.g. a wolf ownership check
     * resolving the owner of a tamed wolf) and you need the controller
     * connection behind it.
     */
    public Optional<DualPlayerSession> byMounted(final UUID mountedUuid) {
        for (final DualPlayerSession s : byController.values()) {
            if (s.mounted() != null && s.mounted().getUUID().equals(mountedUuid)) return Optional.of(s);
        }
        return Optional.empty();
    }

    public void unregister(final UUID controllerUuid) {
        byController.remove(controllerUuid);
    }

    public Collection<DualPlayerSession> all() {
        return Collections.unmodifiableCollection(byController.values());
    }

    public boolean isMountedUuid(final UUID uuid) {
        return byMounted(uuid).isPresent();
    }
}
