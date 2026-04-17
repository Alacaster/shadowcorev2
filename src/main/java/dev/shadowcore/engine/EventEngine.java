package dev.shadowcore.engine;

import dev.shadowcore.manager.Manager;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Main-thread-only event queue. Every command and automatic trigger becomes
 * an {@link EngineEvent}, enters this queue, and is drained synchronously
 * in FIFO order by the {@link Manager}.
 *
 * <p>Spec §10: "The event queue drains on the main server thread. The event
 * queue resolves all queued events before the reconcile pass runs."</p>
 *
 * <p>We achieve FIFO + main-thread with a simple scheduler tick. Submission
 * from any thread is allowed (commands run on main; async pre-login is
 * async; we serialize on entry).</p>
 */
public final class EventEngine {
    private final Plugin plugin;
    private final Manager manager;
    private final Logger log;
    private final Deque<EngineEvent> queue = new ArrayDeque<>();
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private int flushTaskId = -1;

    public EventEngine(final Plugin plugin, final Manager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.log = plugin.getLogger();
    }

    public void start() {
        if (flushTaskId != -1) return;
        // Drain once per tick.
        flushTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L).getTaskId();
    }

    public void stop() {
        if (flushTaskId != -1) {
            Bukkit.getScheduler().cancelTask(flushTaskId);
            flushTaskId = -1;
        }
        // Flush remaining events synchronously if we're on main.
        if (Bukkit.isPrimaryThread()) drain();
    }

    /** Submit an event from any thread. */
    public void submit(final EngineEvent event) {
        synchronized (queue) { queue.addLast(event); }
        if (Bukkit.isPrimaryThread()) drain();
    }

    private void drain() {
        if (!Bukkit.isPrimaryThread()) return;
        if (!draining.compareAndSet(false, true)) return;
        try {
            for (;;) {
                final EngineEvent ev;
                synchronized (queue) { ev = queue.pollFirst(); }
                if (ev == null) break;
                try {
                    manager.dispatch(ev);
                } catch (final RuntimeException ex) {
                    log.severe("Event handler threw " + ex.getClass().getSimpleName() + " for "
                        + ev.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            }
        } finally {
            draining.set(false);
        }
    }
}
