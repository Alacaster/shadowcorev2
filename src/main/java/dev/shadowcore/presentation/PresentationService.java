package dev.shadowcore.presentation;

import com.mojang.authlib.GameProfile;
import dev.shadowcore.core.nms.DualPlayerRegistry;
import dev.shadowcore.core.nms.DualPlayerSession;
import dev.shadowcore.model.MountedIdentity;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;

/**
 * Presentation policy — spec §14, sync spec §3.2, and PHANTOM mode.
 *
 * <h2>Policy matrix per mounted kind</h2>
 * <table>
 *   <caption>Observer views</caption>
 *   <tr><th>Kind</th><th>Controller's own tab</th><th>Others' tab</th><th>World body</th></tr>
 *   <tr><td>MAIN</td>    <td>Self (normal)</td>    <td>Self (normal)</td>    <td>Controller, visible</td></tr>
 *   <tr><td>LOCAL</td>   <td>Mounted profile</td>  <td>Mounted profile</td>  <td>Mounted, visible</td></tr>
 *   <tr><td>SHADOW</td>  <td>Baseline (hidden target)</td>
 *                        <td>Baseline only — target hidden from tab</td>
 *                        <td>Mounted target, visible</td></tr>
 *   <tr><td>PHANTOM</td> <td>Baseline (but self is removed from PlayerInfo too)</td>
 *                        <td>REMOVED from tab entirely</td>
 *                        <td>Baseline with INVISIBILITY effect, no name, default skin</td></tr>
 * </table>
 *
 * <h2>How we enforce PHANTOM presentation</h2>
 * <ol>
 *   <li><b>Tab removal</b> — broadcast a PlayerInfo REMOVE for the baseline
 *       UUID to every observer. Modern clients handle self-removal gracefully
 *       (they keep first-person rendering).</li>
 *   <li><b>World invisibility</b> — handled by {@link
 *       dev.shadowcore.core.nms.MountKernel} adding the vanilla
 *       INVISIBILITY mob effect to the baseline ServerPlayer. Legitimate
 *       clients skip rendering the body.</li>
 *   <li><b>Identity scrubbing</b> — hack clients that bypass PlayerInfo
 *       REMOVE will see whatever profile entry was most recently ADD'd. The
 *       removal alone leaves them nothing to render, but if they also cache
 *       the pre-phantom entry, they would still know who it is. Full
 *       textures-stripping requires outbound packet rewriting via
 *       ProtocolLib; see {@link SkinCloak} which is wired when ProtocolLib
 *       is present.</li>
 * </ol>
 */
public final class PresentationService {
    private final Logger log;
    private final DualPlayerRegistry registry;
    /** Optional ProtocolLib-backed skin cloak; null when ProtocolLib is absent. */
    private SkinCloak cloak;

    public PresentationService(final Logger log, final DualPlayerRegistry registry) {
        this.log = log;
        this.registry = registry;
    }

    /** Called at plugin enable, after ProtocolLib availability is determined. */
    public void attachCloak(final SkinCloak cloak) {
        this.cloak = cloak;
    }

    /**
     * Apply the current mount's presentation policy on every surface.
     */
    public void refreshForSession(final DualPlayerSession session) {
        if (session == null) return;
        final MountedIdentity identity = session.mountedIdentity();
        if (identity == null) {
            restoreBaselinePresentation(session);
            return;
        }
        switch (identity.kind()) {
            case MAIN, LOCAL -> {
                if (cloak != null) cloak.clear(session.controllerUuid());
                restoreBaselinePresentation(session);
                hideControllerFromOthersIfDualBody(session);
            }
            case SHADOW -> {
                if (cloak != null) cloak.clear(session.controllerUuid());
                hideControllerFromOthersIfDualBody(session);
                hideShadowTargetFromTab(session);
            }
            case PHANTOM -> {
                if (cloak != null) cloak.enableFor(session.controllerUuid());
                applyPhantomPresentation(session);
            }
        }
    }

    /**
     * In dual-body modes, remove the controller-side UUID from every
     * observer's PlayerInfo so they only see the mounted identity.
     */
    private void hideControllerFromOthersIfDualBody(final DualPlayerSession session) {
        if (session.isSelfMounted()) return;
        final UUID controllerUuid = session.controllerUuid();
        final var removePacket = new ClientboundPlayerInfoRemovePacket(List.of(controllerUuid));
        broadcastExceptSelf(session, removePacket);
        session.realConnection().send(removePacket);
    }

    /**
     * Shadow mode: hide the shadow target's tab entry from everyone.
     * Sync §3.2: "Other players do not see shadow presence in the client
     * player list either."
     */
    private void hideShadowTargetFromTab(final DualPlayerSession session) {
        final ServerPlayer mounted = session.mounted();
        if (mounted == null || mounted == session.controller()) return;
        final UUID mountedUuid = mounted.getUUID();
        final var removePacket = new ClientboundPlayerInfoRemovePacket(List.of(mountedUuid));
        Bukkit.getOnlinePlayers().forEach(p -> sendTo(p, removePacket));
    }

    /**
     * Phantom presentation — remove baseline from everyone's tab.
     */
    private void applyPhantomPresentation(final DualPlayerSession session) {
        final UUID baselineUuid = session.controller().getUUID();
        final var removePacket = new ClientboundPlayerInfoRemovePacket(List.of(baselineUuid));
        Bukkit.getOnlinePlayers().forEach(p -> sendTo(p, removePacket));
    }

    /**
     * Restore the normal PlayerInfo entry for a session that exited
     * SHADOW/PHANTOM. Broadcast the current mounted-side (or controller
     * for self-mount) as ADD_PLAYER.
     */
    private void restoreBaselinePresentation(final DualPlayerSession session) {
        final ServerPlayer visible = session.mounted() == null ? session.controller() : session.mounted();
        if (visible == null) return;
        try {
            final var addPacket = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(
                    ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED
                ),
                List.of(visible)
            );
            Bukkit.getOnlinePlayers().forEach(p -> sendTo(p, addPacket));
        } catch (final RuntimeException ex) {
            log.warning("restoreBaselinePresentation broadcast failed: " + ex.getMessage());
        }
    }

    /**
     * Re-broadcast mounts to a newly joined observer so their world view is
     * consistent with current policy.
     */
    public void onObserverJoined(final org.bukkit.entity.Player observer) {
        for (final DualPlayerSession s : registry.all()) {
            final MountedIdentity identity = s.mountedIdentity();
            if (identity == null) continue;
            switch (identity.kind()) {
                case MAIN, LOCAL -> {
                    final ServerPlayer visible = s.mounted();
                    if (visible == null) continue;
                    try {
                        sendTo(observer, new ClientboundPlayerInfoUpdatePacket(
                            EnumSet.of(
                                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED
                            ),
                            List.of(visible)
                        ));
                        if (!s.isSelfMounted()) {
                            sendTo(observer, new ClientboundPlayerInfoRemovePacket(List.of(s.controllerUuid())));
                        }
                    } catch (final RuntimeException ex) {
                        log.warning("onObserverJoined MAIN/LOCAL failed: " + ex.getMessage());
                    }
                }
                case SHADOW -> {
                    if (s.mounted() != null && !s.isSelfMounted()) {
                        sendTo(observer, new ClientboundPlayerInfoRemovePacket(List.of(s.mounted().getUUID())));
                        sendTo(observer, new ClientboundPlayerInfoRemovePacket(List.of(s.controllerUuid())));
                    }
                }
                case PHANTOM -> {
                    sendTo(observer, new ClientboundPlayerInfoRemovePacket(List.of(s.controllerUuid())));
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────

    private void broadcastExceptSelf(final DualPlayerSession session,
                                     final net.minecraft.network.protocol.Packet<?> packet) {
        final UUID controllerUuid = session.controllerUuid();
        Bukkit.getOnlinePlayers().forEach(p -> {
            if (p.getUniqueId().equals(controllerUuid)) return;
            sendTo(p, packet);
        });
    }

    private static void sendTo(final org.bukkit.entity.Player observer,
                               final net.minecraft.network.protocol.Packet<?> packet) {
        try {
            final ServerPlayer sp = ((org.bukkit.craftbukkit.entity.CraftPlayer) observer).getHandle();
            sp.connection.send(packet);
        } catch (final RuntimeException ex) {
            // non-fatal — observer may have disconnected
        }
    }
}
