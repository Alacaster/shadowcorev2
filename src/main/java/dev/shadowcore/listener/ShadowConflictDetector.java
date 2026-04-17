package dev.shadowcore.listener;

import dev.shadowcore.engine.EngineEvent;
import dev.shadowcore.engine.EventEngine;
import dev.shadowcore.engine.ResponseHandle;
import dev.shadowcore.manager.Manager;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Detects the spec §10 priority "real target activation wins over shadow
 * continuation" at login time.
 *
 * <p>When a real account whose name is currently being shadowed by another
 * controller logs in, we enqueue a {@link EngineEvent.ShadowConflictArrived}
 * event on the shadowing controller. The manager resolves that into a
 * forced-shadow-end and conflict-shell entry per spec §12.</p>
 *
 * <p>We do this at {@link PlayerJoinEvent} rather than pre-login because
 * pre-login is async; we want the manager's main-thread dispatch loop to
 * handle the consequences in proper order with any competing commands that
 * may already be queued.</p>
 */
public final class ShadowConflictDetector implements Listener {
    private final EventEngine engine;
    private final Manager manager;

    public ShadowConflictDetector(final EventEngine engine, final Manager manager) {
        this.engine = engine;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(final PlayerJoinEvent event) {
        final String arrivingName = event.getPlayer().getName();
        final UUID arrivingUuid = event.getPlayer().getUniqueId();
        final Optional<UUID> shadower = manager.shadowReservationFor(arrivingName);
        if (shadower.isEmpty()) return;
        if (shadower.get().equals(arrivingUuid)) return; // self-shadow, no conflict
        engine.submit(new EngineEvent.ShadowConflictArrived(
            shadower.get(), arrivingName, arrivingUuid, ResponseHandle.silent()));
    }
}
