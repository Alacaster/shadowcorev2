package dev.shadowcore.core.swap;

import dev.shadowcore.model.MountedIdentity;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of in-flight identity swaps.
 *
 * <p>When {@link TransferIdentitySwap} initiates a swap, it records the
 * target identity here keyed by the controller's Mojang UUID. The
 * controller's client then receives a transfer packet and reconnects. When
 * the reconnect reaches {@code AsyncPlayerPreLoginEvent}, the gateway
 * consults this registry to decide whether to rewrite the incoming UUID.
 * When the reconnect completes (PlayerJoinEvent), the manager clears the
 * entry.</p>
 *
 * <p>Thread-safe. {@link #put} is called on the main thread from the
 * swap call site. {@link #poll} is called from
 * {@code AsyncPlayerPreLoginEvent}, which runs on a Netty I/O thread.
 * {@link #complete} is called on the main thread from
 * {@code PlayerJoinEvent}.</p>
 *
 * <p>Not persisted to disk. A server crash mid-swap loses the pending
 * record; the controller reconnects as their main identity, which is
 * the safe fallback (they were trying to become someone else but we
 * don't remember what, so we drop them at main).</p>
 */
public final class PendingSwapRegistry {
    /**
     * One pending-swap record.
     */
    public static final class Pending {
        private final MountedIdentity targetIdentity;
        private final long issuedAtMillis;

        public Pending(final MountedIdentity targetIdentity) {
            this.targetIdentity = Objects.requireNonNull(targetIdentity);
            this.issuedAtMillis = System.currentTimeMillis();
        }

        public MountedIdentity targetIdentity() { return targetIdentity; }
        public long issuedAtMillis() { return issuedAtMillis; }
    }

    /** Pending swaps timing out this many milliseconds after issue are treated as stale. */
    private static final long STALE_AFTER_MILLIS = 30_000L;

    private final Map<UUID, Pending> byControllerMojangUuid = new ConcurrentHashMap<>();

    /**
     * Record a pending swap. Must be called before the transfer packet is
     * sent, so the reconnect's pre-login event finds the record.
     */
    public void put(final UUID controllerMojangUuid, final MountedIdentity targetIdentity) {
        Objects.requireNonNull(controllerMojangUuid);
        Objects.requireNonNull(targetIdentity);
        byControllerMojangUuid.put(controllerMojangUuid, new Pending(targetIdentity));
    }

    /**
     * Look up a pending swap without removing it. Called from
     * {@code AsyncPlayerPreLoginEvent}. Returns empty if no record exists
     * or the record is stale.
     */
    public Optional<Pending> peek(final UUID controllerMojangUuid) {
        if (controllerMojangUuid == null) return Optional.empty();
        final Pending p = byControllerMojangUuid.get(controllerMojangUuid);
        if (p == null) return Optional.empty();
        if (System.currentTimeMillis() - p.issuedAtMillis() > STALE_AFTER_MILLIS) {
            byControllerMojangUuid.remove(controllerMojangUuid, p);
            return Optional.empty();
        }
        return Optional.of(p);
    }

    /**
     * Look up and atomically remove a pending swap. Called from
     * {@code AsyncPlayerPreLoginEvent} once the gateway has confirmed it
     * will consume the rewrite. Using poll rather than peek+remove avoids
     * a race where two near-simultaneous reconnects from the same Mojang
     * UUID both claim the same pending record.
     */
    public Optional<Pending> poll(final UUID controllerMojangUuid) {
        if (controllerMojangUuid == null) return Optional.empty();
        final Pending p = byControllerMojangUuid.remove(controllerMojangUuid);
        if (p == null) return Optional.empty();
        if (System.currentTimeMillis() - p.issuedAtMillis() > STALE_AFTER_MILLIS) {
            return Optional.empty();
        }
        return Optional.of(p);
    }

    /**
     * Idempotent remove. Called from {@code PlayerJoinEvent} as a belt-and-
     * suspenders cleanup in case {@link #poll} was skipped somewhere.
     */
    public void complete(final UUID controllerMojangUuid) {
        if (controllerMojangUuid != null) {
            byControllerMojangUuid.remove(controllerMojangUuid);
        }
    }

    public int size() {
        return byControllerMojangUuid.size();
    }
}
