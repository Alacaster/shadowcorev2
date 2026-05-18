package dev.shadowcore.listener;

import dev.shadowcore.core.swap.Session;
import dev.shadowcore.core.swap.SessionRegistry;
import dev.shadowcore.engine.EngineEvent;
import dev.shadowcore.engine.EventEngine;
import dev.shadowcore.engine.ResponseHandle;
import dev.shadowcore.manager.Manager;
import dev.shadowcore.presentation.PresentationService;
import java.util.logging.Logger;
import java.util.UUID;
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
 * Translates Bukkit lifecycle events into {@link EngineEvent}s and
 * maintains the {@link SessionRegistry}.
 *
 * <p>Simplified from the 3.0 version: no PacketRouter wiring (the doll
 * packet classifier is gone), no MountKernel interactions (IdentitySwap
 * handles those from the manager). All this listener does is:</p>
 *
 * <ul>
 *   <li>On join: construct a {@link Session}, register it, submit
 *       {@link EngineEvent.ControllerJoin} to the engine, and on the
 *       next tick call {@code presentation.onObserverJoined} so the new
 *       observer's tab list reflects any active shadow sessions.</li>
 *   <li>On quit: submit {@link EngineEvent.ControllerQuit}, then on
 *       the next tick (after manager has had a chance to persist)
 *       unregister the session.</li>
 *   <li>On game-mode change: submit
 *       {@link EngineEvent.ControllerGameModeChange} so the manager
 *       can resolve any deferred-restore shell on spectator→other
 *       transitions.</li>
 * </ul>
 */
public final class PlayerLifecycleListener implements Listener {
    private final Logger log;
    private final Plugin plugin;
    private final EventEngine engine;
    private final SessionRegistry registry;
    private final PresentationService presentation;
    private final dev.shadowcore.core.auth.AuthGateway auth;
    @SuppressWarnings("unused") // retained for future event-scoped manager hooks
    private final Manager manager;

    public PlayerLifecycleListener(final Plugin plugin, final Logger log,
                                   final EventEngine engine, final SessionRegistry registry,
                                   final PresentationService presentation,
                                   final Manager manager,
                                   final dev.shadowcore.core.auth.AuthGateway auth) {
        this.plugin = plugin;
        this.log = log;
        this.engine = engine;
        this.registry = registry;
        this.presentation = presentation;
        this.manager = manager;
        this.auth = auth;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player bukkitPlayer = event.getPlayer();
        dev.shadowcore.util.Diag.info(log, "life",
            "onJoin: " + bukkitPlayer.getName() + " (" + bukkitPlayer.getUniqueId() + ")");
        final ServerPlayer nms;
        try {
            nms = ((org.bukkit.craftbukkit.entity.CraftPlayer) bukkitPlayer).getHandle();
        } catch (final RuntimeException ex) {
            dev.shadowcore.util.Diag.error(log, "life",
                "Could not unwrap NMS handle for " + bukkitPlayer.getName(), ex);
            return;
        }

        // The joining player's UUID may be a local profile's synthetic UUID
        // rather than the controller's Mojang UUID — that's what happens
        // after a transfer-reconnect with pending-swap rewrite, and in the
        // direct-alt-login case (someone joins with a profile's name). A
        // Session is always keyed on the Mojang UUID of the human, never
        // on the profile UUID. So we resolve: if the joining UUID is a
        // known local profile, use the profile's owner_uuid for the session.
        final UUID controllerMojangUuid = resolveControllerMojangUuid(bukkitPlayer.getUniqueId());

        final Session session = new Session(
            controllerMojangUuid, nms, nms.connection.connection);
        registry.register(session);
        dev.shadowcore.util.Diag.trace(log, "life",
            "session registered for controller " + controllerMojangUuid
            + " (joining-uuid=" + bukkitPlayer.getUniqueId() + ")");
        engine.submit(new EngineEvent.ControllerJoin(
            controllerMojangUuid, bukkitPlayer.getName(), ResponseHandle.silent()));
        // After the manager has mounted this session (queued above; drains on
        // the next tick), re-broadcast existing mounts to it.
        Bukkit.getScheduler().runTask(plugin, () -> {
            dev.shadowcore.util.Diag.trace(log, "life",
                "onJoin deferred: calling presentation.onObserverJoined for " + bukkitPlayer.getName());
            presentation.onObserverJoined(bukkitPlayer);
        });
    }

    /**
     * Return the Mojang UUID of the human behind the joining player.
     * Delegates to {@link dev.shadowcore.core.auth.AuthGateway#resolveControllerMojangUuid}.
     */
    private UUID resolveControllerMojangUuid(final UUID joiningUuid) {
        return auth.resolveControllerMojangUuid(joiningUuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final Player bukkitPlayer = event.getPlayer();
        final UUID controllerMojangUuid = resolveControllerMojangUuid(bukkitPlayer.getUniqueId());
        dev.shadowcore.util.Diag.info(log, "life",
            "onQuit: " + bukkitPlayer.getName() + " (joining-uuid=" + bukkitPlayer.getUniqueId()
            + " controllerMojangUuid=" + controllerMojangUuid + ")");
        engine.submit(new EngineEvent.ControllerQuit(controllerMojangUuid, ResponseHandle.silent()));
        // Defer by one tick so the engine drain (which will persist state)
        // runs before we tear down the registry entry.
        Bukkit.getScheduler().runTask(plugin, () -> {
            dev.shadowcore.util.Diag.trace(log, "life",
                "onQuit deferred: unregistering session for " + controllerMojangUuid);
            registry.unregister(controllerMojangUuid);
        });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onGameModeChange(final PlayerGameModeChangeEvent event) {
        final UUID controllerMojangUuid = resolveControllerMojangUuid(event.getPlayer().getUniqueId());
        dev.shadowcore.util.Diag.trace(log, "life",
            "onGameModeChange: " + event.getPlayer().getName() + " → " + event.getNewGameMode()
            + " (controllerMojangUuid=" + controllerMojangUuid + ")");
        engine.submit(new EngineEvent.ControllerGameModeChange(
            controllerMojangUuid, event.getNewGameMode().name(), ResponseHandle.silent()));
    }
}
