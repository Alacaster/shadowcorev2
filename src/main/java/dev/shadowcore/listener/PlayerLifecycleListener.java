package dev.shadowcore.listener;

import dev.shadowcore.core.nms.DualPlayerRegistry;
import dev.shadowcore.core.nms.DualPlayerSession;
import dev.shadowcore.core.nms.PacketRouter;
import dev.shadowcore.engine.EngineEvent;
import dev.shadowcore.engine.EventEngine;
import dev.shadowcore.engine.ResponseHandle;
import dev.shadowcore.manager.Manager;
import dev.shadowcore.presentation.PresentationService;
import java.util.logging.Logger;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Translates Bukkit lifecycle events into {@link EngineEvent}s and performs
 * NMS-level session registration / de-registration.
 */
public final class PlayerLifecycleListener implements Listener {
    private final Logger log;
    private final Plugin plugin;
    private final EventEngine engine;
    private final DualPlayerRegistry registry;
    private final PacketRouter router;
    private final PresentationService presentation;
    private final Manager manager;

    public PlayerLifecycleListener(final Plugin plugin, final Logger log,
                                   final EventEngine engine, final DualPlayerRegistry registry,
                                   final PacketRouter router, final PresentationService presentation,
                                   final Manager manager) {
        this.plugin = plugin;
        this.log = log;
        this.engine = engine;
        this.registry = registry;
        this.router = router;
        this.presentation = presentation;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player bukkitPlayer = event.getPlayer();
        final ServerPlayer nms;
        try {
            nms = ((org.bukkit.craftbukkit.entity.CraftPlayer) bukkitPlayer).getHandle();
        } catch (final RuntimeException ex) {
            log.warning("Could not unwrap NMS handle for " + bukkitPlayer.getName() + ": " + ex.getMessage());
            return;
        }
        final DualPlayerSession session = new DualPlayerSession(
            bukkitPlayer.getUniqueId(), nms, nms.connection.connection);
        registry.register(session);
        engine.submit(new EngineEvent.ControllerJoin(
            bukkitPlayer.getUniqueId(), bukkitPlayer.getName(), ResponseHandle.silent()));
        // After the manager has mounted this session (queued above; drains on
        // the next tick), re-broadcast existing mounts to it.
        Bukkit.getScheduler().runTask(plugin, () -> presentation.onObserverJoined(bukkitPlayer));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final Player bukkitPlayer = event.getPlayer();
        engine.submit(new EngineEvent.ControllerQuit(bukkitPlayer.getUniqueId(), ResponseHandle.silent()));
        // Tear down NMS-level routing AFTER the manager has a chance to
        // persist. The controller-quit is synchronous (same tick), so we
        // defer by one tick to let the drain finish.
        Bukkit.getScheduler().runTask(plugin, () -> {
            final DualPlayerSession session = registry.byController(bukkitPlayer.getUniqueId()).orElse(null);
            if (session != null) {
                router.removeFromController(session.realConnection());
            }
            registry.unregister(bukkitPlayer.getUniqueId());
        });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onGameModeChange(final PlayerGameModeChangeEvent event) {
        engine.submit(new EngineEvent.ControllerGameModeChange(
            event.getPlayer().getUniqueId(), event.getNewGameMode().name(), ResponseHandle.silent()));
    }
}
