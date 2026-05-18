package dev.shadowcore.presentation;

import dev.shadowcore.core.swap.Session;
import dev.shadowcore.core.swap.SessionRegistry;
import dev.shadowcore.model.MountedIdentity;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;

/**
 * Presentation policy for 4.0.
 *
 * <p>Scope has shrunk dramatically from the 3.0 version. The 3.0 service
 * had to maintain consistency between a controller ServerPlayer and a
 * separate "doll" ServerPlayer on the same channel — hiding the
 * controller from tab, filtering dual-body visibility, and fighting
 * leaks from the shared Netty pipe via a ProtocolLib-backed SkinCloak.
 * The 4.0 service only has one ServerPlayer per active identity driving
 * the socket, so there is nothing to filter between.</p>
 *
 * <h2>What this service now does</h2>
 * <ul>
 *   <li>Shadow mode: hide the shadow target's tab entry from observers
 *       that are not the shadow controller itself. (The controller sees
 *       the target in their own tab — locked-in decision — so we only
 *       broadcast REMOVE_PLAYER for the target UUID to <em>other</em>
 *       observers.)</li>
 *   <li>When a new observer joins, re-apply the above policy for every
 *       live shadow session so their initial tab is consistent.</li>
 *   <li>Hold an optional reference to {@link SkinCloak} so ProtocolLib
 *       can override textures if needed.</li>
 * </ul>
 *
 * <p>Main-thread only.</p>
 */
public final class PresentationService {
    private final Logger log;
    private final SessionRegistry registry;
    private SkinCloak cloak;

    public PresentationService(final Logger log, final SessionRegistry registry) {
        this.log = log;
        this.registry = registry;
    }

    /** Attach the SkinCloak once ProtocolLib availability is determined. Nullable. */
    public void attachCloak(final SkinCloak cloak) {
        this.cloak = cloak;
    }

    /**
     * Apply the current mount's presentation policy for a session. Called
     * after every successful mount transition by Manager.
     */
    public void refreshForSession(final Session session) {
        if (session == null) return;
        final MountedIdentity identity = session.currentIdentity();
        dev.shadowcore.util.Diag.info(log, "pres",
            "refreshForSession: controller=" + session.controllerUuid()
            + " identity=" + (identity == null ? "none" : identity.kind() + ":" + identity.displayName()));
        if (identity == null) return;
        switch (identity.kind()) {
            case MAIN, LOCAL -> {
                if (cloak != null) cloak.clear(session.controllerUuid());
                // No special presentation needed. The ServerPlayer IS the
                // identity; vanilla tab broadcasts handle the rest.
            }
            case SHADOW -> {
                if (cloak != null) cloak.clear(session.controllerUuid());
                hideShadowTargetFromOtherObservers(session);
            }
            case PHANTOM -> {
                // PHANTOM was a 3.0 workaround for dual-body visibility leaks.
                // In 4.0 there is no second body, so there's nothing to hide
                // at the presentation layer. If this kind is encountered in
                // legacy DB state, treat it as MAIN/LOCAL.
                if (cloak != null) cloak.clear(session.controllerUuid());
            }
        }
    }

    /**
     * Shadow mode: broadcast a PlayerInfoRemove for the shadow target's
     * UUID to every observer, including the controller themselves. The tab
     * list stays pre-shadow for everyone — no new entry appears for the
     * target. The baseline stays in tab because its ServerPlayer is still
     * in PlayerList.
     */
    private void hideShadowTargetFromOtherObservers(final Session session) {
        final ServerPlayer currentBody = session.current();
        if (currentBody == null) return;
        final UUID targetUuid = currentBody.getUUID();
        final UUID controllerUuid = session.controllerUuid();
        final var removePacket = new ClientboundPlayerInfoRemovePacket(List.of(targetUuid));
        dev.shadowcore.util.Diag.info(log, "pres",
            "hideShadowTargetFromOtherObservers: hiding " + targetUuid
            + " from all observers (controller=" + controllerUuid + ")");
        Bukkit.getOnlinePlayers().forEach(p -> sendTo(p, removePacket));
    }

    /**
     * Re-apply the shadow-tab-hide policy for a newly-joined observer, so
     * their initial tab list is consistent with every active shadow
     * session. Called from PlayerLifecycleListener on PlayerJoin.
     */
    public void onObserverJoined(final org.bukkit.entity.Player observer) {
        for (final Session s : registry.all()) {
            final MountedIdentity identity = s.currentIdentity();
            if (identity == null) continue;
            if (identity.kind() != MountedIdentity.Kind.SHADOW) continue;
            final ServerPlayer currentBody = s.current();
            if (currentBody == null) continue;
            try {
                sendTo(observer, new ClientboundPlayerInfoRemovePacket(
                    List.of(currentBody.getUUID())));
            } catch (final RuntimeException ex) {
                log.warning("onObserverJoined shadow-hide failed: " + ex.getMessage());
            }
        }
    }

    private static void sendTo(final org.bukkit.entity.Player observer,
                               final net.minecraft.network.protocol.Packet<?> packet) {
        try {
            final ServerPlayer sp = ((org.bukkit.craftbukkit.entity.CraftPlayer) observer).getHandle();
            sp.connection.send(packet);
        } catch (final RuntimeException ex) {
            // non-fatal — observer may have disconnected mid-send
        }
    }
}
