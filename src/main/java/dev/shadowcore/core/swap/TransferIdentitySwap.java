package dev.shadowcore.core.swap;

import dev.shadowcore.core.nms.MountBackup;
import dev.shadowcore.model.MountDisposition;
import dev.shadowcore.model.MountedIdentity;
import dev.shadowcore.util.Diag;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * The real identity-swap implementation.
 *
 * <p>Identity switches work by sending the client a transfer packet naming
 * the server's own host and port. The client disconnects from the current
 * socket and reconnects. When the reconnect reaches
 * {@code AsyncPlayerPreLoginEvent}, the {@link PendingSwapRegistry} is
 * consulted and the event's UUID is rewritten to the target identity's
 * UUID. Vanilla login then places the client on the correct
 * {@link ServerPlayer}.</p>
 *
 * <p>Scope as of step 2 of the roadmap: profile switches only (MAIN ↔
 * LOCAL). Shadow-related operations ({@link #enterConflictShell},
 * {@link #resolveDeferredRestore}, shadow mounts and ends) delegate to a
 * fallback {@link IdentitySwap} instance — the stub, during the skeleton
 * phase. Shadow functionality lands in step 5.</p>
 *
 * <p>All public methods are main-thread only.</p>
 */
public final class TransferIdentitySwap implements IdentitySwap {
    private final Logger log;
    private final MinecraftServer nmsServer;
    private final MountBackup backup;
    private final PendingSwapRegistry pending;
    private final ParkingChamber parking;
    private final dev.shadowcore.store.Database db;
    private final SessionRegistry registry;

    public TransferIdentitySwap(final Logger log,
                                final MinecraftServer nmsServer,
                                final MountBackup backup,
                                final PendingSwapRegistry pending,
                                final ParkingChamber parking,
                                final dev.shadowcore.store.Database db,
                                final SessionRegistry registry) {
        this.log = log;
        this.nmsServer = nmsServer;
        this.backup = backup;
        this.pending = pending;
        this.parking = parking;
        this.db = db;
        this.registry = registry;
    }

    // ──────────────────────────────────────────────────────────────
    //  Profile switching — the real implementation.
    // ──────────────────────────────────────────────────────────────

    @Override
    public boolean mount(final Session session, final MountedIdentity identity) {
        // Initial mount at session bootstrap. The controller's client just
        // connected and the ServerPlayer is already the correct identity —
        // there's nothing to transfer.
        Diag.info(log, "swap",
            "mount (initial): " + session.controllerUuid() + " -> " + show(identity));
        // Capture a pre-mount backup of this identity's .dat so a future
        // /lprofile discard (or DISCARD-disposition swap) has a snapshot
        // to roll back to. This is the discard-point: changes from now
        // until the next swap can be discarded back to this state.
        if (session.current() != null) {
            try {
                backup.capture(session.current().getUUID());
            } catch (final Exception ex) {
                Diag.warn(log, "swap", "mount: backup.capture failed for "
                    + session.current().getUUID() + ": " + ex.getMessage());
            }
        }
        session.setCurrent(session.current(), identity, identity.baseline());
        return true;
    }

    @Override
    public boolean swap(final Session session, final MountedIdentity next,
                        final MountDisposition dispositionForOld) {
        if (next != null && next.kind() == MountedIdentity.Kind.SHADOW) {
            return performShadowMount(session, next, dispositionForOld);
        }
        if (session.currentIdentity() != null
            && session.currentIdentity().kind() == MountedIdentity.Kind.SHADOW) {
            return performShadowEnd(session, next, dispositionForOld);
        }
        return performProfileSwap(session, next, dispositionForOld);
    }

    /**
     * The real transfer-reconnect sequence for MAIN ↔ LOCAL swaps.
     */
    private boolean performProfileSwap(final Session session, final MountedIdentity next,
                                       final MountDisposition dispositionForOld) {
        // The Bukkit Player object for the currently-driving socket is
        // keyed on the active ServerPlayer's UUID, NOT the controller's
        // Mojang UUID. While on a local profile, Bukkit.getPlayer(mojangUuid)
        // returns null because no online player has that UUID — the only
        // online Player has the profile's synthetic UUID. We look up via
        // the active ServerPlayer's UUID instead.
        final Player controller = session.current() != null
            ? Bukkit.getPlayer(session.current().getUUID())
            : null;
        if (controller == null || !controller.isOnline()) {
            Diag.warn(log, "swap", "performProfileSwap: active ServerPlayer "
                + (session.current() == null ? "<null>" : session.current().getUUID())
                + " for controller " + session.controllerUuid()
                + " is not online; aborting swap");
            return false;
        }

        Diag.info(log, "swap",
            "performProfileSwap: controller=" + controller.getName()
            + " -> " + show(next) + " (old disposition=" + dispositionForOld + ")");

        // (1) Capture a backup of the outgoing identity's .dat so we can
        // restore it later if a future mount of this identity uses
        // DISCARD disposition. Also used during THIS swap's save/discard
        // decision below.
        try {
            backup.capture(session.current().getUUID());
        } catch (final Exception ex) {
            Diag.warn(log, "swap",
                "performProfileSwap: backup.capture failed for outgoing "
                + session.current().getUUID() + ": " + ex.getMessage());
            // Non-fatal. Proceed with the swap; discard-later may be
            // unavailable but commit will still work.
        }

        // (2) Save or discard outgoing.
        if (dispositionForOld == MountDisposition.DISCARD) {
            try {
                backup.restore(session.current().getUUID());
                Diag.info(log, "swap",
                    "performProfileSwap: restored outgoing .dat from backup (DISCARD)");
            } catch (final Exception ex) {
                Diag.warn(log, "swap",
                    "performProfileSwap: backup.restore failed: " + ex.getMessage());
            }
        } else {
            // COMMIT: save via vanilla path. This writes the ServerPlayer's
            // current state (inventory, position, XP, etc.) to its .dat.
            try {
                dev.shadowcore.core.nms.NmsCompat.savePlayerData(nmsServer.getPlayerList(), session.current());
                Diag.info(log, "swap",
                    "performProfileSwap: saved outgoing via PlayerList.save (COMMIT)");
            } catch (final Exception ex) {
                Diag.warn(log, "swap",
                    "performProfileSwap: PlayerList.save failed: " + ex.getMessage());
                // Non-fatal — proceed. The client is still going to get
                // kicked by the transfer packet either way; better to
                // complete the swap than to leave them in a half-state.
            }
        }

        // (3) Record the pending swap. Keyed on the controller's Mojang
        // UUID — that's what AsyncPlayerPreLoginEvent will carry for the
        // reconnect.
        pending.put(session.controllerUuid(), next);
        Diag.info(log, "swap",
            "performProfileSwap: recorded pending swap for " + session.controllerUuid()
            + " -> " + show(next));

        // (4) Send a user-friendly message before we yank the socket. The
        // "Connecting..." screen will appear momentarily; this chat line
        // at least logs what's happening.
        try {
            controller.sendMessage(Component.text("[ShadowCore] ", NamedTextColor.GOLD)
                .append(Component.text("switching identity to " + next.displayName() + "...",
                    NamedTextColor.YELLOW)));
        } catch (final RuntimeException ignored) {}

        // (5) Send the transfer packet. This causes the client to
        // disconnect from the current socket and reconnect.
        final boolean transferred = sendTransferToSelf(controller);
        if (!transferred) {
            Diag.error(log, "swap",
                "performProfileSwap: transfer packet send failed for "
                + controller.getName() + "; clearing pending swap",
                null);
            pending.complete(session.controllerUuid());
            return false;
        }

        // Bookkeeping on the Session doesn't complete here — that happens
        // when the reconnect's PlayerJoinEvent fires and the manager sees
        // the new ServerPlayer. Until then, the session's `current` still
        // points to the now-disconnecting ServerPlayer, which will shortly
        // be evicted by vanilla's disconnect handling. We accept that brief
        // inconsistency because we're not using that ServerPlayer for
        // anything after this point.
        return true;
    }

    // ──────────────────────────────────────────────────────────────
    //  Shadow mount and shadow end.
    // ──────────────────────────────────────────────────────────────

    /**
     * Begin a shadow session. Park the outgoing baseline in a sealed
     * bedrock chamber at a fresh remote region (so its UUID stays online
     * for pets/pearls/plugins), capture a backup of the target's .dat,
     * record a pending swap, and transfer-reconnect the controller into
     * the target identity.
     *
     * <p>The parked baseline {@code ServerPlayer} continues to exist in
     * {@link net.minecraft.server.players.PlayerList} after the controller's
     * client disconnects for the transfer. Its {@code connection} field is
     * replaced with a {@link dev.shadowcore.core.nms.NoopConnection} just
     * before the transfer fires, so vanilla's disconnect handling does
     * not evict it. The chamber chunk is force-loaded for the duration.</p>
     */
    private boolean performShadowMount(final Session session, final MountedIdentity next,
                                       final MountDisposition dispositionForOld) {
        final Player controller = session.current() != null
            ? Bukkit.getPlayer(session.current().getUUID())
            : null;
        if (controller == null || !controller.isOnline()) {
            Diag.warn(log, "swap", "performShadowMount: active ServerPlayer not online; aborting");
            return false;
        }
        Diag.info(log, "swap", "performShadowMount: " + controller.getName()
            + " -> SHADOW:" + next.displayName() + " (target uuid=" + next.uuid() + ")");

        // 1. Save the outgoing baseline so its .dat reflects pre-shadow
        //    state. The parked ServerPlayer continues running in memory,
        //    but if the server crashes mid-shadow we want disk to have
        //    the baseline as it was at shadow start.
        try {
            dev.shadowcore.core.nms.NmsCompat.savePlayerData(
                nmsServer.getPlayerList(), session.current());
            Diag.info(log, "swap", "performShadowMount: saved outgoing baseline");
        } catch (final Exception ex) {
            Diag.warn(log, "swap",
                "performShadowMount: PlayerList.save on baseline failed: " + ex.getMessage());
        }

        // 2. Capture a backup of the target's .dat for DISCARD on shadow
        //    end. If the target has no .dat (never logged in here), this
        //    creates an empty backup which restore() will handle as
        //    "delete the .dat we just created."
        try {
            backup.capture(next.uuid());
            Diag.info(log, "swap", "performShadowMount: captured target backup for " + next.uuid());
        } catch (final Exception ex) {
            Diag.warn(log, "swap",
                "performShadowMount: backup.capture for target failed: " + ex.getMessage());
        }

        // 3. Build the parking chamber. Park in the controller's current
        //    world so pets in that world don't sit due to world-change.
        final org.bukkit.World currentWorld = controller.getWorld();
        final java.util.Optional<ParkingChamber.Build> built = parking.buildIn(currentWorld);
        if (built.isEmpty()) {
            Diag.error(log, "swap",
                "performShadowMount: parking chamber construction failed; aborting", null);
            return false;
        }
        final ParkingChamber.Build chamber = built.get();
        Diag.info(log, "swap", "performShadowMount: chamber built at "
            + chamber.parkLocation() + " in " + chamber.worldName());

        // 4. Move the baseline ServerPlayer into the chamber and apply
        //    parking effects. Done before the connection swap so the
        //    teleport packets reach the (still-attached) client briefly,
        //    though the immediate transfer will overwrite the visual.
        try {
            controller.teleport(chamber.parkLocation());
            applyParkingEffects(controller);
        } catch (final RuntimeException ex) {
            Diag.warn(log, "swap",
                "performShadowMount: pre-park teleport/effects failed: " + ex.getMessage());
        }

        // 5. Replace the baseline's connection with a NoopConnection so
        //    vanilla's disconnect handler does not remove it from
        //    PlayerList when the controller's client disconnects for
        //    the transfer.
        final net.minecraft.server.level.ServerPlayer baselineNms = session.current();
        final dev.shadowcore.core.nms.NoopConnection noop;
        try {
            noop = new dev.shadowcore.core.nms.NoopConnection();
            // Replace the field directly. ServerPlayer's `connection`
            // is the ServerGamePacketListenerImpl whose `.connection`
            // is the underlying Connection. We swap the underlying
            // Connection, leaving the listener pointing at our noop.
            baselineNms.connection.connection = noop;
            Diag.info(log, "swap", "performShadowMount: baseline connection swapped to NoopConnection");
        } catch (final RuntimeException | LinkageError ex) {
            Diag.error(log, "swap",
                "performShadowMount: NoopConnection install failed: " + ex.getMessage(), ex);
            // Recover: clear chamber so we don't leak a region file.
            parking.tearDown(chamber.worldName(), chamber.regionX(), chamber.regionZ());
            return false;
        }

        // 6. Track parked baseline in session + secondary registry index.
        session.setParkedBaseline(baselineNms);
        registry.registerParkedBaseline(session, baselineNms.getUUID());

        // 7. Persist parking-region for cleanup tracking.
        db.recordParkingRegion(session.controllerUuid(),
            chamber.worldName(), chamber.regionX(), chamber.regionZ());

        // 8. Record pending swap to target.
        pending.put(session.controllerUuid(), next);

        // 9. Transfer the controller's client.
        try {
            controller.sendMessage(net.kyori.adventure.text.Component
                .text("[ShadowCore] ", net.kyori.adventure.text.format.NamedTextColor.GOLD)
                .append(net.kyori.adventure.text.Component
                    .text("entering shadow as " + next.displayName() + "...",
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW)));
        } catch (final RuntimeException ignored) {}
        if (!sendTransferToSelf(controller)) {
            Diag.error(log, "swap", "performShadowMount: transfer failed; rolling back parking", null);
            pending.complete(session.controllerUuid());
            session.clearParkedBaseline();
            db.clearParkingRegion(session.controllerUuid());
            parking.tearDown(chamber.worldName(), chamber.regionX(), chamber.regionZ());
            return false;
        }
        return true;
    }

    /**
     * End a shadow session: discard or save target state, evict the
     * parked baseline ServerPlayer from PlayerList, tear down the chamber,
     * delete the region file, and transfer-reconnect the controller into
     * the baseline identity.
     */
    private boolean performShadowEnd(final Session session, final MountedIdentity baselineAsNext,
                                     final MountDisposition dispositionForOld) {
        final Player controller = session.current() != null
            ? Bukkit.getPlayer(session.current().getUUID())
            : null;
        if (controller == null || !controller.isOnline()) {
            Diag.warn(log, "swap", "performShadowEnd: active ServerPlayer not online; aborting");
            return false;
        }
        Diag.info(log, "swap", "performShadowEnd: " + controller.getName()
            + " (SHADOW:" + session.currentIdentity().displayName() + ") -> "
            + show(baselineAsNext) + " (disposition=" + dispositionForOld + ")");

        // 1. Save or discard the target's state.
        if (dispositionForOld == MountDisposition.DISCARD) {
            try {
                backup.restore(session.current().getUUID());
                Diag.info(log, "swap", "performShadowEnd: target .dat restored from backup (DISCARD)");
            } catch (final Exception ex) {
                Diag.warn(log, "swap",
                    "performShadowEnd: backup.restore on target failed: " + ex.getMessage());
            }
        } else {
            try {
                dev.shadowcore.core.nms.NmsCompat.savePlayerData(
                    nmsServer.getPlayerList(), session.current());
                Diag.info(log, "swap", "performShadowEnd: target saved (COMMIT)");
            } catch (final Exception ex) {
                Diag.warn(log, "swap",
                    "performShadowEnd: PlayerList.save on target failed: " + ex.getMessage());
            }
        }

        // 2. Evict the parked baseline from PlayerList. Its NoopConnection
        //    means vanilla's normal disconnect path won't touch a real
        //    socket; we just need it gone from PlayerList so the
        //    upcoming transfer-reconnect can place a fresh ServerPlayer
        //    at the same Mojang UUID.
        final net.minecraft.server.level.ServerPlayer parked = session.parkedBaseline();
        if (parked != null) {
            try {
                nmsServer.getPlayerList().remove(parked);
                Diag.info(log, "swap", "performShadowEnd: parked baseline evicted from PlayerList");
            } catch (final Exception ex) {
                Diag.warn(log, "swap",
                    "performShadowEnd: PlayerList.remove on parked baseline failed: " + ex.getMessage());
            }
            registry.clearParkedBaseline(parked.getUUID());
            session.clearParkedBaseline();
        } else {
            Diag.warn(log, "swap", "performShadowEnd: no parked baseline tracked in session");
        }

        // 3. Tear down the chamber + delete the region file.
        final java.util.Optional<dev.shadowcore.store.Database.ParkingRegion> region =
            db.getParkingRegion(session.controllerUuid());
        if (region.isPresent()) {
            final var r = region.get();
            parking.tearDown(r.worldName(), r.regionX(), r.regionZ());
            db.clearParkingRegion(session.controllerUuid());
        } else {
            Diag.warn(log, "swap", "performShadowEnd: no parking region tracked for "
                + session.controllerUuid());
        }

        // 4. Record pending swap to baseline + transfer-reconnect.
        pending.put(session.controllerUuid(), baselineAsNext);
        try {
            controller.sendMessage(net.kyori.adventure.text.Component
                .text("[ShadowCore] ", net.kyori.adventure.text.format.NamedTextColor.GOLD)
                .append(net.kyori.adventure.text.Component
                    .text("ending shadow, returning to " + baselineAsNext.displayName() + "...",
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW)));
        } catch (final RuntimeException ignored) {}
        if (!sendTransferToSelf(controller)) {
            Diag.error(log, "swap", "performShadowEnd: transfer failed", null);
            pending.complete(session.controllerUuid());
            return false;
        }
        return true;
    }

    /**
     * Apply parking potion effects: RESISTANCE 4, SATURATION,
     * FIRE_RESISTANCE, INVISIBILITY. All infinite, hidden from HUD.
     */
    private void applyParkingEffects(final Player p) {
        final int infinite = -1; // PotionEffect.INFINITE_DURATION sentinel
        try {
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.RESISTANCE, infinite, 4, false, false, false));
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SATURATION, infinite, 0, false, false, false));
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE, infinite, 0, false, false, false));
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.INVISIBILITY, infinite, 0, false, false, false));
            Diag.trace(log, "swap", "applyParkingEffects: applied to " + p.getName());
        } catch (final RuntimeException ex) {
            Diag.warn(log, "swap", "applyParkingEffects: " + ex.getMessage());
        }
    }

    /**
     * Send {@code ClientboundTransferPacket} to the controller pointing at
     * the same server. Uses Paper's {@code Player#transfer(host, port)} API
     * if available; otherwise falls back to constructing the packet and
     * sending it via NMS.
     *
     * @return true if the transfer was dispatched; false if all paths failed.
     */
    private boolean sendTransferToSelf(final Player controller) {
        final String host = resolveHost();
        final int port = org.bukkit.Bukkit.getPort();
        Diag.trace(log, "swap",
            "sendTransferToSelf: host=" + host + " port=" + port);

        // Attempt 1 — Paper public API. Available since Paper 1.20.5.
        try {
            // Player#transfer(String, int) — documented as sending a
            // ClientboundTransferPacket and disconnecting the player.
            controller.transfer(host, port);
            Diag.info(log, "swap",
                "sendTransferToSelf: via Player#transfer OK (" + host + ":" + port + ")");
            return true;
        } catch (final NoSuchMethodError notAvailable) {
            Diag.trace(log, "swap",
                "sendTransferToSelf: Player#transfer not available, falling back to NMS");
        } catch (final RuntimeException ex) {
            Diag.warn(log, "swap",
                "sendTransferToSelf: Player#transfer threw: " + ex.getMessage());
            // Fall through to the NMS path in case it works despite the
            // API throwing.
        }

        // Attempt 2 — NMS packet construction. Only used if the API path
        // above didn't work. The packet class lives in
        // net.minecraft.network.protocol.common.ClientboundTransferPacket
        // in 1.21.11 mojang-mapped Paper.
        try {
            final ServerPlayer sp = ((org.bukkit.craftbukkit.entity.CraftPlayer) controller).getHandle();
            final Object packet = buildTransferPacketReflectively(host, port);
            if (packet == null) {
                Diag.error(log, "swap",
                    "sendTransferToSelf: could not construct transfer packet via reflection",
                    null);
                return false;
            }
            sp.connection.send((net.minecraft.network.protocol.Packet<?>) packet);
            Diag.info(log, "swap",
                "sendTransferToSelf: via NMS packet OK (" + host + ":" + port + ")");
            return true;
        } catch (final RuntimeException ex) {
            Diag.error(log, "swap",
                "sendTransferToSelf: NMS packet send failed: " + ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Construct {@code ClientboundTransferPacket} via reflection so we
     * compile cleanly even if the class path differs slightly between
     * Paper builds. Returns null if the class or constructor cannot be
     * located.
     */
    private static Object buildTransferPacketReflectively(final String host, final int port) {
        final String[] candidateClassNames = new String[] {
            "net.minecraft.network.protocol.common.ClientboundTransferPacket",
            "net.minecraft.network.protocol.configuration.ClientboundTransferPacket",
        };
        for (final String className : candidateClassNames) {
            try {
                final Class<?> cls = Class.forName(className);
                // Expected signature: ClientboundTransferPacket(String host, int port)
                final java.lang.reflect.Constructor<?> ctor = cls.getConstructor(String.class, int.class);
                return ctor.newInstance(host, port);
            } catch (final ClassNotFoundException notHere) {
                // Try next candidate
            } catch (final ReflectiveOperationException other) {
                return null;
            }
        }
        return null;
    }

    /**
     * The host to put in the transfer packet. Preference order:
     *
     * <ol>
     *   <li>Configured "forced host" (future config key; not yet wired).</li>
     *   <li>The server's bind address if it's a real hostname.</li>
     *   <li>"localhost" as a last resort (works for test servers).</li>
     * </ol>
     *
     * <p>If the player originally connected via a domain name that resolves
     * to the server, using "localhost" or the bind IP will still cause a
     * reconnect — the client just connects to whatever address is in the
     * packet. On a Velocity setup the correct host is the one the client
     * sees (e.g., play.example.com), which is why a future config key for
     * explicit host override matters.</p>
     */
    private String resolveHost() {
        final String ip = org.bukkit.Bukkit.getIp();
        if (ip != null && !ip.isBlank() && !"0.0.0.0".equals(ip)) {
            return ip;
        }
        return "localhost";
    }

    // ──────────────────────────────────────────────────────────────
    //  Non-profile operations — delegated to the shadow fallback
    //  until the shadow-related roadmap steps ship.
    // ──────────────────────────────────────────────────────────────

    @Override
    public void persist(final Session session) {
        // Save outgoing ServerPlayer via vanilla path. Safe to always do.
        try {
            if (session.current() != null) {
                dev.shadowcore.core.nms.NmsCompat.savePlayerData(nmsServer.getPlayerList(), session.current());
                Diag.trace(log, "swap", "persist: saved " + session.current().getUUID());
            }
        } catch (final Exception ex) {
            Diag.warn(log, "swap", "persist: PlayerList.save failed: " + ex.getMessage());
        }
    }

    @Override
    public void unmount(final Session session, final MountDisposition disposition) {
        // Currently delegated — unmount at controller-quit is a vanilla
        // path (the player disconnects normally). Real 4.0 behavior is a
        // save or discard plus PlayerList.remove, which vanilla already
        // handles on disconnect. For now we just persist if COMMIT.
        if (disposition == MountDisposition.COMMIT) {
            persist(session);
        }
        Diag.info(log, "swap",
            "unmount: " + session.controllerUuid() + " (disposition=" + disposition + ")");
    }

    @Override
    public boolean reload(final Session session) {
        // Nothing in the 4.0 control flow currently calls reload(). The
        // /lprofile discard path used to call this for MAIN/LOCAL states
        // but was disabled to prevent inventory-drop duping. Shadow discard
        // goes through swap() with DISCARD disposition, not reload.
        // Method retained for interface compatibility; if a future flow
        // legitimately needs in-place .dat rollback for the active
        // identity, implement it here.
        Diag.warn(log, "swap",
            "reload: called but not implemented — no current code path needs it");
        return false;
    }

    @Override
    public void enterConflictShell(final Session session) {
        Diag.warn(log, "swap",
            "enterConflictShell: not yet implemented — landing in step 9");
    }

    @Override
    public boolean resolveDeferredRestore(final Session session,
                                          final MountedIdentity baselineAsMount,
                                          final boolean resetPosition,
                                          final GameMode requestedMode) {
        Diag.warn(log, "swap",
            "resolveDeferredRestore: not yet implemented — landing in step 9");
        return false;
    }

    // ──────────────────────────────────────────────────────────────

    private static String show(final MountedIdentity i) {
        return i == null ? "null" : (i.kind() + ":" + i.displayName());
    }
}
