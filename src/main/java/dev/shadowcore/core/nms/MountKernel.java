package dev.shadowcore.core.nms;

import com.mojang.authlib.GameProfile;
import dev.shadowcore.model.MountDisposition;
import dev.shadowcore.model.MountedIdentity;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.bukkit.GameMode;

/**
 * The NMS core of ShadowCore — mount, unmount, swap, persist.
 *
 * <h2>Architectural model</h2>
 * One real Netty {@link net.minecraft.network.Connection} per controller
 * (the physical client connection). The mounted-side {@link ServerPlayer}
 * lives in {@link PlayerList#players} so vanilla ownership and world
 * lookups hit it, and its {@link ServerGamePacketListenerImpl} is wired to
 * a {@link HeadlessConnection} that forwards all outbound traffic into the
 * controller's real connection. Vanilla's
 * {@link PlayerList#placeNewPlayer placeNewPlayer} is not used verbatim:
 * it presumes a freshly-authenticated connection and would clobber the
 * configured state of the controller's real connection. We instead
 * replicate the subset of its behavior we need.
 *
 * <h2>Spec enforcement</h2>
 * <ul>
 *   <li>§5 — vanilla save paths are authoritative; we call
 *       {@link PlayerList#save} and {@link PlayerList#load}, never write
 *       .dat files directly.</li>
 *   <li>§2.7 — changes to .dat happen by mounting then running normal save.</li>
 *   <li>§20 — ender pearls, wolves, beds, projectiles belong to the mounted
 *       identity by construction because the mounted {@link ServerPlayer}'s
 *       UUID equals the mounted identity's UUID.</li>
 * </ul>
 *
 * <h2>Known gaps requiring runtime validation</h2>
 * <ol>
 *   <li>The controller-side {@link ServerPlayer} remains present in
 *       {@link PlayerList#players}; the presentation layer is responsible
 *       for filtering it out of every observable surface.</li>
 *   <li>{@link MountDisposition#DISCARD} — vanilla's
 *       {@link PlayerList#remove} always saves. {@link MountBackup} takes
 *       a pre-mount .dat backup and restores it after remove when DISCARD
 *       was requested.</li>
 *   <li>{@link PlayerListAccess#registerMounted} needs reflective field
 *       access because modern Paper no longer has a public API for inserting
 *       a {@link ServerPlayer} into the players list outside of the
 *       {@link PlayerList#placeNewPlayer} path.</li>
 * </ol>
 *
 * <p>All methods are main-thread only.</p>
 */
public final class MountKernel {
    private final Logger log;
    private final MinecraftServer server;
    private final PlayerList playerList;
    private final DualPlayerRegistry registry;
    private final PacketRouter router;
    private final MountBackup backup;
    /** Optional — set via {@link #attachSkinResolver} after construction. */
    private dev.shadowcore.core.auth.SkinResolver skinResolver;

    public MountKernel(final Logger log, final MinecraftServer server,
                       final DualPlayerRegistry registry, final PacketRouter router,
                       final MountBackup backup) {
        this.log = Objects.requireNonNull(log);
        this.server = Objects.requireNonNull(server);
        this.playerList = server.getPlayerList();
        this.registry = Objects.requireNonNull(registry);
        this.router = Objects.requireNonNull(router);
        this.backup = Objects.requireNonNull(backup);
    }

    public void attachSkinResolver(final dev.shadowcore.core.auth.SkinResolver resolver) {
        this.skinResolver = resolver;
    }

    // ────────────────────────────────────────────────────────────────
    //  Public mount API
    // ────────────────────────────────────────────────────────────────

    public boolean mount(final DualPlayerSession session, final MountedIdentity identity) {
        if (session.mounted() != null) {
            log.warning("mount() on an already-mounted session; caller must use swap().");
            return false;
        }
        // Phantom mount — self-shadow. The world actor stays the baseline
        // ServerPlayer and we apply presentation + invisibility overlays.
        if (identity.kind() == MountedIdentity.Kind.PHANTOM) {
            return applyPhantomMount(session, identity);
        }
        // Fast path: if the identity IS the controller's own main, no second
        // ServerPlayer is constructed — we self-mount.
        if (identity.kind() == MountedIdentity.Kind.MAIN
            && identity.uuid().equals(session.controllerUuid())) {
            return applySelfMount(session, identity);
        }
        // Local profile for the controller (baseline == LOCAL, mount == same LOCAL).
        // The baseline has a distinct profile UUID; we still need a distinct
        // ServerPlayer for the local profile identity because its UUID differs
        // from the controller's real UUID.
        return applyExternalMount(session, identity);
    }

    public boolean swap(final DualPlayerSession session, final MountedIdentity next,
                        final MountDisposition dispositionForOld) {
        if (session.mounted() == null) return mount(session, next);
        removeMounted(session, dispositionForOld);
        return mount(session, next);
    }

    public void persist(final DualPlayerSession session) {
        final ServerPlayer mounted = session.mounted();
        if (mounted == null || mounted == session.controller()) return;
        savePlayerData(mounted);
    }

    public void unmount(final DualPlayerSession session, final MountDisposition disposition) {
        removeMounted(session, disposition);
    }

    /**
     * Reload the current mounted identity from its on-disk .dat, discarding
     * any in-memory changes since the last save. Implements spec §11
     * "discard" for MAIN_MOUNTED and LOCAL_PROFILE_MOUNTED: "Reload the
     * current identity from the current discard point."
     *
     * <p>For a local-profile mount (dual-body), this is a clean swap with
     * DISCARD disposition on the current mount — the mount backup captured
     * at mount time is restored over the .dat before the next placement
     * reads it, so the identity comes back to its pre-mount state.</p>
     *
     * <p>For a self-mount (controller == mounted, MAIN case), there is no
     * second body to remove/replace. We instead fall back to the vanilla
     * {@link net.minecraft.server.players.PlayerList#load} path, which
     * reads the .dat and applies it to the existing controller
     * {@link ServerPlayer}. Any live-but-unsaved changes are dropped.
     * The controller's connection is not disturbed — only entity state
     * resets.</p>
     *
     * @return true on success.
     */
    public boolean reload(final DualPlayerSession session) {
        final ServerPlayer mounted = session.mounted();
        final MountedIdentity identity = session.mountedIdentity();
        if (mounted == null || identity == null) return false;
        if (mounted != session.controller()) {
            // Dual-body — swap with DISCARD preserves the .dat from the
            // pre-mount backup, then remount reads it back.
            return swap(session, identity, MountDisposition.DISCARD);
        }
        // Self-mount — vanilla reload on the controller ServerPlayer.
        try {
            loadPlayerData(mounted);
            mounted.getBukkitEntity().updateInventory();
            return true;
        } catch (final RuntimeException ex) {
            log.severe("reload failed on self-mount: " + ex.getMessage());
            return false;
        }
    }

    public void enterConflictShell(final DualPlayerSession session) {
        if (session.mounted() != null) {
            removeMounted(session, MountDisposition.COMMIT);
        }
        session.enterConflictShell();
        final ServerPlayer controller = session.controller();
        try {
            controller.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        } catch (final RuntimeException ex) {
            log.warning("Failed to park controller in spectator shell: " + ex.getMessage());
        }
        router.setPolicy(session.realConnection(),
            new PacketRouter.PacketRoutingPolicy(null,
                PacketRouter.InboundDestination.CONTROLLER, () -> {}, server));
    }

    public boolean resolveDeferredRestore(final DualPlayerSession session,
                                          final MountedIdentity baselineAsMount,
                                          final boolean resetPosition,
                                          final GameMode requestedMode) {
        if (!session.conflictShell()) return false;
        if (!mount(session, baselineAsMount)) return false;
        session.clearConflictShell();
        return true;
    }

    // ────────────────────────────────────────────────────────────────
    //  Internals
    // ────────────────────────────────────────────────────────────────

    private boolean applySelfMount(final DualPlayerSession session, final MountedIdentity identity) {
        session.setMounted(session.controller(), identity, identity.baseline());
        router.installOnController(session.realConnection());
        router.setPolicy(session.realConnection(),
            buildPolicy(session.controller().connection, identity));
        return true;
    }

    /**
     * Phantom mount — self-shadow of the controller's real name (or, from
     * a local profile baseline, a self-shadow of the local profile name).
     *
     * <p>No second ServerPlayer is constructed. The baseline {@link
     * ServerPlayer} remains the world actor. We apply vanilla-level
     * invisibility so that legitimate clients do not render the body, and
     * we set a marker on the session so the {@link
     * dev.shadowcore.presentation.PresentationService} knows to:</p>
     *
     * <ul>
     *   <li>Remove the baseline UUID from every observer's PlayerInfo list.</li>
     *   <li>Suppress ADD_PLAYER re-broadcasts to late-joining observers.</li>
     *   <li>Rewrite the GameProfile to a blank-name / default-skin facade for
     *       any clientbound packet that leaks the identity (handled at the
     *       headless-connection filter level on the rare cases it applies —
     *       since there's no second ServerPlayer, most leaks are the
     *       baseline's own self-view, which is fine and expected).</li>
     * </ul>
     *
     * <p>The baseline is already the mounted player, so the controller is
     * fully in control of its own body; third-party ownership checks that
     * ask "who owns this pearl?" correctly resolve to the baseline UUID.
     * The only thing that differs from a normal baseline mount is the
     * outward-facing identity presentation.</p>
     */
    private boolean applyPhantomMount(final DualPlayerSession session, final MountedIdentity identity) {
        // The baseline must match the controller connection's current body.
        // For a main-account self-shadow, baseline.worldActorUuid() equals
        // session.controllerUuid(). For a local-profile self-shadow, the
        // local profile ServerPlayer is already mounted — we only apply
        // presentation changes on top.
        final ServerPlayer worldActor = session.controller();
        session.setMounted(worldActor, identity, identity.baseline());
        router.installOnController(session.realConnection());
        router.setPolicy(session.realConnection(),
            buildPolicy(worldActor.connection, identity));

        // Apply world-level invisibility so sane clients do not render.
        // The phantom flag is cleared on unmount by removing the effect.
        try {
            final var invisibilityEffect = new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.INVISIBILITY,
                Integer.MAX_VALUE, 0, true, false, false);
            worldActor.addEffect(invisibilityEffect);
        } catch (final RuntimeException ex) {
            log.warning("Could not apply invisibility for phantom mount: " + ex.getMessage());
        }
        return true;
    }

    private boolean applyExternalMount(final DualPlayerSession session, final MountedIdentity identity) {
        final UUID uuid = identity.uuid();
        final String name = identity.displayName();
        final GameProfile profile = new GameProfile(uuid, name);
        // For shadow mounts, apply the target's skin texture if we've cached
        // it via SkinResolver. Local-profile mounts reuse the controller's
        // skin by default (handled downstream by the presentation layer).
        if (identity.kind() == MountedIdentity.Kind.SHADOW && skinResolver != null) {
            skinResolver.cached(uuid).ifPresent(p ->
                dev.shadowcore.core.auth.SkinResolver.applyProperty(profile, p));
        }
        addProfileToServerCache(profile);

        final ServerLevel overworld = server.overworld();
        final net.minecraft.server.level.ClientInformation clientInfo = copyControllerClientInfo(session);

        final ServerPlayer doll;
        try {
            doll = new ServerPlayer(server, overworld, profile, clientInfo);
        } catch (final RuntimeException ex) {
            log.severe("ServerPlayer construction failed for " + uuid + ": " + ex.getMessage());
            return false;
        }

        // Pre-mount backup so DISCARD can restore on unmount.
        backup.capture(uuid);

        // Load stored data via vanilla. PlayerList#load reads world/playerdata/<uuid>.dat
        // (or dimension-specific equivalents on 1.21) and invokes ServerPlayer#load.
        // If no .dat exists, vanilla leaves the doll in its construction-default state.
        try {
            loadPlayerData(doll);
        } catch (final RuntimeException ex) {
            log.warning("playerList.load for " + uuid + " failed (fresh identity likely): " + ex.getMessage());
        }

        // Wire the headless connection + game-packet listener.
        final HeadlessConnection headless = HeadlessConnection.create();
        headless.forwardTo(session.realConnection());
        final CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        final ServerGamePacketListenerImpl listener =
            new ServerGamePacketListenerImpl(server, headless, doll, cookie);
        // The listener constructor should have set doll.connection; ensure it.
        if (doll.connection != listener) {
            try {
                // Field assignment — doll.connection is a final/public field in modern NMS.
                final var field = ServerPlayer.class.getDeclaredField("connection");
                field.setAccessible(true);
                field.set(doll, listener);
            } catch (final ReflectiveOperationException ex) {
                log.severe("Could not assign listener to mounted ServerPlayer: " + ex.getMessage());
                return false;
            }
        }

        // Add the doll to the world's tick + track lists.
        overworld.addNewPlayer(doll);

        // Register in PlayerList#players / #playersByUUID / #playersByName.
        if (!PlayerListAccess.registerMounted(playerList, doll)) {
            log.severe("PlayerListAccess.registerMounted failed; mount aborted.");
            return false;
        }

        // Broadcast ADD_PLAYER so everyone (including the controller-as-client)
        // sees the mounted identity in their PlayerInfo list. The presentation
        // layer rewrites this per observer in shadow mode.
        playerList.broadcastAll(new ClientboundPlayerInfoUpdatePacket(
            EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED
            ),
            List.of(doll)
        ));

        // Commit session state + routing policy.
        session.setMounted(doll, identity, identity.baseline());
        router.installOnController(session.realConnection());
        router.installOutboundMute(session.realConnection(), null);
        router.setPolicy(session.realConnection(), buildPolicy(listener, identity));
        hideController(session);
        return true;
    }

    private void removeMounted(final DualPlayerSession session, final MountDisposition disposition) {
        final ServerPlayer mounted = session.mounted();
        if (mounted == null) {
            session.setMounted(null, null, session.baseline());
            return;
        }
        // Phantom mount — clear the invisibility effect we added and exit.
        final MountedIdentity identity = session.mountedIdentity();
        if (identity != null && identity.isPhantom()) {
            try {
                mounted.removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY);
            } catch (final RuntimeException ex) {
                log.warning("Could not clear phantom invisibility on unmount: " + ex.getMessage());
            }
            session.setMounted(null, null, session.baseline());
            return;
        }
        if (mounted == session.controller()) {
            // Self-mount: nothing to remove; clear state only.
            session.setMounted(null, null, session.baseline());
            return;
        }
        final UUID uuid = mounted.getUUID();
        try {
            playerList.remove(mounted);
        } catch (final RuntimeException ex) {
            log.severe("PlayerList.remove failed for " + uuid + ": " + ex.getMessage());
        }
        if (disposition == MountDisposition.DISCARD) {
            backup.restore(uuid);
        } else {
            backup.drop(uuid);
        }
        session.setMounted(null, null, session.baseline());
    }

    private net.minecraft.server.level.ClientInformation copyControllerClientInfo(final DualPlayerSession session) {
        return session.controller().clientInformation();
    }

    private void addProfileToServerCache(final GameProfile profile) {
        try {
            final var getProfileCache = server.getClass().getMethod("getProfileCache");
            final Object cache = getProfileCache.invoke(server);
            if (cache == null) return;
            final var add = cache.getClass().getMethod("add", GameProfile.class);
            add.invoke(cache, profile);
        } catch (final ReflectiveOperationException ignored) {
            // Mapping drift: cache insertion is optional for runtime correctness.
        }
    }

    private void savePlayerData(final ServerPlayer player) {
        if (tryInvokePlayerListPlayerMethod("save", player)) return;
        if (tryInvokePlayerDataStorageMethod("save", player)) return;
        throw new RuntimeException("Unable to invoke save path for ServerPlayer");
    }

    private void loadPlayerData(final ServerPlayer player) {
        if (tryInvokePlayerListPlayerMethod("load", player)) return;
        if (tryInvokePlayerDataStorageMethod("load", player)) return;
        throw new RuntimeException("Unable to invoke load path for ServerPlayer");
    }

    private boolean tryInvokePlayerListPlayerMethod(final String preferredName, final ServerPlayer player) {
        try {
            for (Class<?> c = playerList.getClass(); c != null; c = c.getSuperclass()) {
                for (final var method : c.getDeclaredMethods()) {
                    if (method.getParameterCount() != 1 || !ServerPlayer.class.isAssignableFrom(method.getParameterTypes()[0])) continue;
                    if (!method.getName().equals(preferredName)) continue;
                    method.setAccessible(true);
                    method.invoke(playerList, player);
                    return true;
                }
            }
        } catch (final ReflectiveOperationException ex) {
            return false;
        }
        return false;
    }

    private boolean tryInvokePlayerDataStorageMethod(final String preferredName, final ServerPlayer player) {
        try {
            final Object storage = resolvePlayerDataStorage();
            if (storage == null) return false;
            for (Class<?> c = storage.getClass(); c != null; c = c.getSuperclass()) {
                for (final var method : c.getDeclaredMethods()) {
                    if (method.getParameterCount() != 1 || !ServerPlayer.class.isAssignableFrom(method.getParameterTypes()[0])) continue;
                    if (!method.getName().equals(preferredName)) continue;
                    method.setAccessible(true);
                    method.invoke(storage, player);
                    return true;
                }
            }
        } catch (final ReflectiveOperationException ex) {
            return false;
        }
        return false;
    }

    private Object resolvePlayerDataStorage() throws ReflectiveOperationException {
        final Class<?> serverClass = server.getClass();
        final java.util.Set<String> names = java.util.Set.of(
            "getplayeriostorage", "getplayerio", "playeriostorage", "playerio",
            "getplayerdatastorage", "getplayerdata", "playerdatastorage", "playerdata"
        );
        for (Class<?> c = serverClass; c != null; c = c.getSuperclass()) {
            for (final var method : c.getDeclaredMethods()) {
                if (method.getParameterCount() != 0) continue;
                final String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (!names.contains(name)) continue;
                method.setAccessible(true);
                final Object value = method.invoke(server);
                if (value != null) return value;
            }
        }
        return null;
    }

    private PacketRouter.PacketRoutingPolicy buildPolicy(final ServerGamePacketListenerImpl mountedListener,
                                                         final MountedIdentity identity) {
        // Spec §6:
        //   Plain chat in local profile mode → Mounted identity
        //   Plain chat in shadow mode         → Baseline (controller) identity
        //   Phantom mode is a self-shadow; follows the shadow rule.
        final PacketRouter.InboundDestination chatSource = switch (identity.kind()) {
            case MAIN, LOCAL    -> PacketRouter.InboundDestination.MOUNTED;
            case SHADOW, PHANTOM -> PacketRouter.InboundDestination.CONTROLLER;
        };
        return new PacketRouter.PacketRoutingPolicy(mountedListener, chatSource, () -> {}, server);
    }

    private void hideController(final DualPlayerSession session) {
        final ServerPlayer controller = session.controller();
        try {
            controller.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        } catch (final RuntimeException ex) {
            log.warning("Could not stow controller in spectator: " + ex.getMessage());
        }
    }
}
