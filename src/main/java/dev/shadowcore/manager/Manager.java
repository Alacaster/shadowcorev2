package dev.shadowcore.manager;

import com.mojang.authlib.GameProfile;
import dev.shadowcore.core.auth.AuthGateway;
import dev.shadowcore.core.nms.DualPlayerRegistry;
import dev.shadowcore.core.nms.DualPlayerSession;
import dev.shadowcore.core.nms.MountKernel;
import dev.shadowcore.engine.EngineEvent;
import dev.shadowcore.model.MountDisposition;
import dev.shadowcore.model.MountedIdentity;
import dev.shadowcore.model.ProfileIdentity;
import dev.shadowcore.model.SessionState;
import dev.shadowcore.presentation.PresentationService;
import dev.shadowcore.store.Database;
import dev.shadowcore.store.Database.ProfileRecord;
import dev.shadowcore.store.Database.SessionRecord;
import dev.shadowcore.store.Database.SettingsRecord;
import dev.shadowcore.util.Naming;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * The orchestration manager: dispatches every {@link EngineEvent}, applies
 * the spec's command-by-state matrix (§11) and automatic-event rules (§12),
 * merges contradictory intentions using the priority rules (§10), produces
 * a reconcile plan, and hands it to the {@link MountKernel}.
 *
 * <p>This class is the single place where spec section references correspond
 * to executable logic. Every rejected command produces a user-visible
 * response without mutating any state — per §11 "A rejected command changes
 * nothing."</p>
 *
 * <p>Main-thread only.</p>
 */
public final class Manager {
    private final Logger log;
    private final Database db;
    private final MountKernel kernel;
    private final DualPlayerRegistry registry;
    private final PresentationService presentation;
    private final AuthGateway auth;
    /** Optional — attached by the plugin after EventEngine + SkinResolver are built. */
    private dev.shadowcore.engine.EventEngine engine;
    private dev.shadowcore.core.auth.SkinResolver skinResolver;

    /** Active shadow reservations: lowercase(target) → controller UUID. */
    private final Map<String, UUID> shadowReservations = new HashMap<>();

    public Manager(final Logger log, final Database db, final MountKernel kernel,
                   final DualPlayerRegistry registry, final PresentationService presentation,
                   final AuthGateway auth) {
        this.log = log;
        this.db = db;
        this.kernel = kernel;
        this.registry = registry;
        this.presentation = presentation;
        this.auth = auth;
    }

    public void attachEngine(final dev.shadowcore.engine.EventEngine engine) { this.engine = engine; }
    public void attachSkinResolver(final dev.shadowcore.core.auth.SkinResolver r) { this.skinResolver = r; }

    /** Rebuild runtime state from the orchestration DB on plugin enable. */
    public void bootstrap() {
        shadowReservations.clear();
        for (final SessionRecord s : db.loadAllSessions()) {
            if (s.shadowTargetName() != null) {
                shadowReservations.put(Naming.normalizeKey(s.shadowTargetName()), s.controllerUuid());
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Dispatch
    // ────────────────────────────────────────────────────────────────

    public void dispatch(final EngineEvent event) {
        switch (event) {
            // Local profile
            case EngineEvent.ProfileCreate e     -> onProfileCreate(e);
            case EngineEvent.ProfileSwitch e     -> onProfileSwitch(e);
            case EngineEvent.ProfileList e       -> onProfileList(e);
            case EngineEvent.ProfileRename e     -> onProfileRename(e);
            case EngineEvent.ProfileDelete e     -> onProfileDelete(e);
            case EngineEvent.ProfileStatus e     -> onProfileStatus(e);
            case EngineEvent.ProfileDiscard e    -> onProfileDiscard(e);
            case EngineEvent.ProfileReturnToMain e -> onReturnToMain(e);
            case EngineEvent.ProfileSetLimit e   -> onSetLimit(e);
            case EngineEvent.ProfileAdminDelete e -> onAdminDelete(e);
            // Shadow
            case EngineEvent.ShadowMount e       -> onShadowMount(e);
            case EngineEvent.ShadowNameResolved e -> onShadowNameResolved(e);
            case EngineEvent.ShadowLogout e      -> onShadowLogout(e);
            case EngineEvent.ShadowDiscard e     -> onShadowDiscard(e);
            case EngineEvent.ShadowStatus e      -> onShadowStatus(e);
            // Lifecycle
            case EngineEvent.ControllerJoin e    -> onControllerJoin(e);
            case EngineEvent.ControllerQuit e    -> onControllerQuit(e);
            case EngineEvent.ShadowConflictArrived e -> onShadowConflictArrived(e);
            case EngineEvent.ShadowConflictTargetLeft e -> onShadowConflictTargetLeft(e);
            case EngineEvent.ControllerGameModeChange e -> onControllerGameModeChange(e);
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Stable state inference (§7)
    // ────────────────────────────────────────────────────────────────

    private SessionState stateOf(final UUID controllerUuid) {
        if (!registry.byController(controllerUuid).isPresent()) return SessionState.DISCONNECTED;
        final SessionRecord s = db.loadSession(controllerUuid).orElse(null);
        if (s == null) return SessionState.MAIN_MOUNTED;
        if (s.conflictFrozen()) return SessionState.CONFLICT_SPECTATOR_SHELL;
        if (s.isShadowing()) return SessionState.SHADOW_MOUNTED;
        if (s.isLocalProfile()) return SessionState.LOCAL_PROFILE_MOUNTED;
        return SessionState.MAIN_MOUNTED;
    }

    // ────────────────────────────────────────────────────────────────
    //  Local profile handlers
    // ────────────────────────────────────────────────────────────────

    private void onProfileCreate(final EngineEvent.ProfileCreate e) {
        // §11 Main/Local: allowed. Shadow: allowed (metadata only). Shell: reject.
        final SessionState st = stateOf(e.actor());
        if (st == SessionState.CONFLICT_SPECTATOR_SHELL) {
            e.response().reply("<red>The deferred restore must finish first.</red>");
            return;
        }
        if (st == SessionState.DISCONNECTED) {
            e.response().reply("<red>No player object exists.</red>");
            return;
        }
        final String suffix = Naming.normalizeSuffix(e.suffix());
        if (!Naming.isValidSuffix(suffix)) {
            e.response().reply("<red>Invalid suffix. Use up to 10 alphanumeric characters.</red>");
            return;
        }
        if (db.findProfileBySuffix(e.actor(), suffix).isPresent()) {
            e.response().reply("<red>That suffix is already in use.</red>");
            return;
        }
        final int limit = db.getMaxProfiles(e.actor(), 2);
        final int count = db.listProfiles(e.actor()).size();
        final Player player = Bukkit.getPlayer(e.actor());
        final boolean bypass = player != null && player.hasPermission("shadowcore.profile.bypass-limit");
        if (count >= limit && !bypass) {
            e.response().reply("<red>Profile limit reached (" + limit + "). Ask an admin to raise it.</red>");
            return;
        }
        final UUID newProfileUuid = UUID.randomUUID();
        db.insertProfile(new Database.ProfileRecord(
            newProfileUuid, e.actor(), suffix, System.currentTimeMillis(), 0L, true));
        // Pre-populate the GameProfileCache with the reconstructed display name.
        final String base = db.getAccountName(e.actor()).orElse(e.actorName());
        final String display = Naming.reconstructDisplayName(base, suffix);
        Bukkit.getServer().getLogger().info("ShadowCore: created local profile " + display
            + " for controller " + base + " (" + e.actor() + ")");
        e.response().reply("<green>Created local profile</green> <yellow>" + display + "</yellow><green>.</green>");
    }

    private void onProfileSwitch(final EngineEvent.ProfileSwitch e) {
        final SessionState st = stateOf(e.actor());
        // §11: Main/Local allowed, Shadow rejected, Shell rejected.
        if (st == SessionState.SHADOW_MOUNTED) {
            e.response().reply("<red>Cannot switch profile while shadowing. End shadow first.</red>");
            return;
        }
        if (st == SessionState.CONFLICT_SPECTATOR_SHELL) {
            e.response().reply("<red>The deferred restore must finish first.</red>");
            return;
        }
        if (st == SessionState.DISCONNECTED) {
            e.response().reply("<red>Not connected.</red>");
            return;
        }
        final Optional<ProfileRecord> target = db.findProfileBySuffix(e.actor(), Naming.normalizeSuffix(e.suffix()));
        if (target.isEmpty()) { e.response().reply("<red>No profile with that suffix.</red>"); return; }

        final DualPlayerSession session = registry.byController(e.actor()).orElse(null);
        if (session == null) { e.response().reply("<red>No runtime session.</red>"); return; }

        final ProfileRecord pr = target.get();
        final ProfileIdentity newBaseline = ProfileIdentity.local(e.actor(), pr.profileUuid(), pr.suffix());
        final String base = db.getAccountName(e.actor()).orElse(session.controller().getGameProfile().getName());
        final String display = Naming.reconstructDisplayName(base, pr.suffix());
        final MountedIdentity next = MountedIdentity.ofBaseline(newBaseline, display);

        final boolean ok = kernel.swap(session, next, MountDisposition.COMMIT);
        if (!ok) {
            e.response().reply("<red>Profile switch failed.</red>");
            return;
        }
        db.touchProfileMounted(pr.profileUuid());
        persistSession(e.actor(), pr.profileUuid(), null, null, false, false, null, false, true, null);
        presentation.refreshForSession(session);
        e.response().reply("<green>Mounted</green> <yellow>" + display + "</yellow><green>.</green>");
    }

    private void onProfileList(final EngineEvent.ProfileList e) {
        final List<ProfileRecord> profiles = db.listProfiles(e.actor());
        if (profiles.isEmpty()) {
            e.response().reply("<yellow>You have no local profiles.</yellow>");
            return;
        }
        final String base = db.getAccountName(e.actor()).orElse("player");
        final StringBuilder sb = new StringBuilder("<green>Your local profiles:</green>");
        for (final ProfileRecord p : profiles) {
            sb.append(" <yellow>").append(Naming.reconstructDisplayName(base, p.suffix())).append("</yellow>");
        }
        e.response().reply(sb.toString());
    }

    private void onProfileRename(final EngineEvent.ProfileRename e) {
        final Optional<ProfileRecord> p = db.findProfileBySuffix(e.actor(), Naming.normalizeSuffix(e.oldSuffix()));
        if (p.isEmpty()) { e.response().reply("<red>No profile with that suffix.</red>"); return; }
        if (!p.get().renameable()) { e.response().reply("<red>That profile cannot be renamed.</red>"); return; }
        final String newSuffix = Naming.normalizeSuffix(e.newSuffix());
        if (!Naming.isValidSuffix(newSuffix)) { e.response().reply("<red>Invalid suffix.</red>"); return; }
        if (db.findProfileBySuffix(e.actor(), newSuffix).isPresent()) {
            e.response().reply("<red>That suffix is already in use.</red>");
            return;
        }
        db.updateProfileSuffix(p.get().profileUuid(), newSuffix);
        e.response().reply("<green>Renamed to suffix</green> <yellow>" + newSuffix + "</yellow><green>.</green>");
    }

    private void onProfileDelete(final EngineEvent.ProfileDelete e) {
        final Optional<ProfileRecord> p = db.findProfileBySuffix(e.actor(), Naming.normalizeSuffix(e.suffix()));
        if (p.isEmpty()) { e.response().reply("<red>No profile with that suffix.</red>"); return; }
        final SessionRecord sess = db.loadSession(e.actor()).orElse(null);
        if (sess != null && sess.activeProfileUuid() != null && sess.activeProfileUuid().equals(p.get().profileUuid())) {
            e.response().reply("<red>Cannot delete the active profile. Switch first.</red>");
            return;
        }
        db.deleteProfile(p.get().profileUuid());
        // Intentionally: we do NOT delete the vanilla .dat file. The profile's
        // gameplay data remains in world/playerdata/<uuid>.dat so the operator
        // can recover if deletion was a mistake. Spec §2.8 allows recovery.
        e.response().reply("<green>Deleted local profile.</green>");
    }

    private void onProfileStatus(final EngineEvent.ProfileStatus e) {
        final SessionState st = stateOf(e.actor());
        final SessionRecord sess = db.loadSession(e.actor()).orElse(null);
        final StringBuilder sb = new StringBuilder("<gray>State:</gray> <yellow>").append(st).append("</yellow>");
        if (sess != null) {
            if (sess.activeProfileUuid() != null) {
                final Optional<ProfileRecord> p = db.findProfileByUuid(sess.activeProfileUuid());
                p.ifPresent(pr -> sb.append(" <gray>profile:</gray> <yellow>").append(pr.suffix()).append("</yellow>"));
            }
            if (sess.isShadowing()) {
                sb.append(" <gray>shadowing:</gray> <yellow>").append(sess.shadowTargetName()).append("</yellow>");
            }
            if (sess.conflictFrozen()) {
                sb.append(" <red>[conflict-shell]</red>");
            }
        }
        e.response().reply(sb.toString());
    }

    private void onProfileDiscard(final EngineEvent.ProfileDiscard e) {
        final SessionState st = stateOf(e.actor());
        final DualPlayerSession session = registry.byController(e.actor()).orElse(null);
        if (session == null) { e.response().reply("<red>Not connected.</red>"); return; }

        // §11: discard means different things per state.
        switch (st) {
            case SHADOW_MOUNTED -> {
                // End shadow, discard mounted changes.
                final SessionRecord sess = db.loadSession(e.actor()).orElseThrow();
                final ProfileIdentity baseline = baselineFromSession(e.actor(), sess);
                final String baseName = db.getAccountName(e.actor()).orElse("player");
                final String display = baseline.isMain() ? baseName
                    : Naming.reconstructDisplayName(baseName, baseline.suffix());
                final MountedIdentity restored = MountedIdentity.ofBaseline(baseline, display);
                kernel.swap(session, restored, MountDisposition.DISCARD);
                clearShadowReservation(sess.shadowTargetName());
                persistSession(e.actor(),
                    baseline.isMain() ? null : baseline.profileUuid(),
                    null, null, false, false, null, false, true, null);
                e.response().reply("<green>Shadow ended and changes discarded.</green>");
            }
            case CONFLICT_SPECTATOR_SHELL -> {
                // Resolve deferred restore now.
                final SessionRecord sess = db.loadSession(e.actor()).orElseThrow();
                final ProfileIdentity baseline = baselineFromDeferred(e.actor(), sess);
                final String baseName = db.getAccountName(e.actor()).orElse("player");
                final String display = baseline.isMain() ? baseName
                    : Naming.reconstructDisplayName(baseName, baseline.suffix());
                final MountedIdentity restored = MountedIdentity.ofBaseline(baseline, display);
                kernel.resolveDeferredRestore(session, restored, sess.deferredRestoreResetPosition(), GameMode.SURVIVAL);
                persistSession(e.actor(),
                    baseline.isMain() ? null : baseline.profileUuid(),
                    null, null, false, false, null, false, true, null);
                e.response().reply("<green>Deferred restore resolved.</green>");
            }
            case MAIN_MOUNTED, LOCAL_PROFILE_MOUNTED -> {
                // Spec §11: "Reload the current identity from the current
                // discard point." The kernel's reload() path does this
                // through vanilla load — for a dual-body mount it swaps the
                // current mount out with DISCARD disposition (restoring
                // the pre-mount .dat backup) and remounts; for a self-mount
                // it invokes PlayerList.load directly.
                final boolean ok = kernel.reload(session);
                if (!ok) {
                    e.response().reply("<red>Discard failed. See server log.</red>");
                    return;
                }
                e.response().reply("<green>Live mount changes discarded.</green>");
            }
            case DISCONNECTED -> e.response().reply("<red>Not connected.</red>");
        }
    }

    private void onReturnToMain(final EngineEvent.ProfileReturnToMain e) {
        final SessionState st = stateOf(e.actor());
        if (st == SessionState.CONFLICT_SPECTATOR_SHELL) {
            e.response().reply("<red>The deferred restore must finish first.</red>");
            return;
        }
        if (st == SessionState.MAIN_MOUNTED) {
            e.response().reply("<yellow>Already on main.</yellow>");
            return;
        }
        final DualPlayerSession session = registry.byController(e.actor()).orElse(null);
        if (session == null) { e.response().reply("<red>Not connected.</red>"); return; }
        final ProfileIdentity mainId = ProfileIdentity.main(e.actor());
        final String baseName = db.getAccountName(e.actor()).orElse(session.controller().getGameProfile().getName());
        final MountedIdentity mainMount = MountedIdentity.ofBaseline(mainId, baseName);
        // If currently shadowing, end shadow first (COMMIT by default).
        final SessionRecord sess = db.loadSession(e.actor()).orElse(null);
        if (sess != null && sess.isShadowing()) clearShadowReservation(sess.shadowTargetName());
        kernel.swap(session, mainMount, MountDisposition.COMMIT);
        persistSession(e.actor(), null, null, null, false, false, null, false, true, null);
        presentation.refreshForSession(session);
        e.response().reply("<green>Returned to main.</green>");
    }

    private void onSetLimit(final EngineEvent.ProfileSetLimit e) {
        final Player admin = Bukkit.getPlayer(e.admin());
        if (admin == null || !admin.hasPermission("shadowcore.admin")) {
            e.response().reply("<red>No permission.</red>");
            return;
        }
        db.setMaxProfiles(e.target(), e.limit());
        e.response().reply("<green>Profile limit set to</green> <yellow>" + e.limit() + "</yellow><green>.</green>");
    }

    private void onAdminDelete(final EngineEvent.ProfileAdminDelete e) {
        final Player admin = Bukkit.getPlayer(e.admin());
        if (admin == null || !admin.hasPermission("shadowcore.admin")) {
            e.response().reply("<red>No permission.</red>");
            return;
        }
        final Optional<ProfileRecord> p = db.findProfileBySuffix(e.owner(), Naming.normalizeSuffix(e.suffix()));
        if (p.isEmpty()) { e.response().reply("<red>No profile with that suffix.</red>"); return; }
        // Cannot delete active.
        final SessionRecord sess = db.loadSession(e.owner()).orElse(null);
        if (sess != null && sess.activeProfileUuid() != null && sess.activeProfileUuid().equals(p.get().profileUuid())) {
            e.response().reply("<red>Profile is currently active. Ask the owner to switch first.</red>");
            return;
        }
        db.deleteProfile(p.get().profileUuid());
        e.response().reply("<green>Admin-deleted local profile.</green>");
    }

    // ────────────────────────────────────────────────────────────────
    //  Shadow handlers
    // ────────────────────────────────────────────────────────────────

    private void onShadowMount(final EngineEvent.ShadowMount e) {
        final Player actor = Bukkit.getPlayer(e.actor());
        if (actor == null) { e.response().reply("<red>Not connected.</red>"); return; }
        if (!actor.hasPermission("shadowcore.admin")) { e.response().reply("<red>No permission.</red>"); return; }
        final SessionState st = stateOf(e.actor());
        if (st == SessionState.CONFLICT_SPECTATOR_SHELL) {
            e.response().reply("<red>Cannot shadow while in conflict shell.</red>");
            return;
        }
        final String key = Naming.normalizeKey(e.targetName());
        final UUID existingRes = shadowReservations.get(key);
        if (existingRes != null && !existingRes.equals(e.actor())) {
            e.response().reply("<red>That target is reserved by another shadow session.</red>");
            return;
        }
        // Name resolution: local profiles / synthetics / known real names resolve
        // from the DB synchronously. If we land on a real account UUID and we
        // don't yet have a skin cached for it, prime the skin cache async before
        // dispatching ShadowNameResolved — this way the mount sees a warm cache
        // and shadow dolls show the target's actual skin.
        final UUID resolved = auth.resolveUuidForName(e.targetName()).orElse(null);
        final boolean isReal = db.lookupKnownName(e.targetName()).map(Database.KnownName::isReal).orElse(false);
        final UUID targetUuid = resolved != null ? resolved : db.getOrCreateSyntheticForName(e.targetName());
        // Resolve self-shadow synchronously — the PHANTOM path doesn't use skin at all.
        final SessionRecord sess = db.loadSession(e.actor()).orElse(null);
        final ProfileIdentity baselineForSelfCheck = baselineFromSession(e.actor(), sess);
        final boolean isSelfShadow = targetUuid.equals(baselineForSelfCheck.worldActorUuid());
        if (isSelfShadow || skinResolver == null || !isReal) {
            onShadowNameResolved(new EngineEvent.ShadowNameResolved(
                e.actor(), e.actorName(), e.targetName(), isReal, targetUuid, e.response()));
            return;
        }
        // Warm the skin cache, then dispatch.
        if (skinResolver.cached(targetUuid).isPresent()) {
            onShadowNameResolved(new EngineEvent.ShadowNameResolved(
                e.actor(), e.actorName(), e.targetName(), true, targetUuid, e.response()));
            return;
        }
        e.response().reply("<gray>Resolving target skin…</gray>");
        skinResolver.fetch(targetUuid, prop -> engine.submit(new EngineEvent.ShadowNameResolved(
            e.actor(), e.actorName(), e.targetName(), true, targetUuid, e.response())));
    }

    private void onShadowNameResolved(final EngineEvent.ShadowNameResolved e) {
        final DualPlayerSession session = registry.byController(e.actor()).orElse(null);
        if (session == null) { e.response().reply("<red>Not connected.</red>"); return; }
        final SessionRecord sess = db.loadSession(e.actor()).orElse(null);
        final ProfileIdentity baseline = baselineFromSession(e.actor(), sess);
        final boolean selfShadow = e.resolvedUuid().equals(baseline.worldActorUuid());
        // Self-shadow → PHANTOM mode. The world actor stays the baseline
        // ServerPlayer and the presentation layer enforces identity hiding
        // + invisibility. No second ServerPlayer is constructed.
        final MountedIdentity mount = selfShadow
            ? MountedIdentity.ofPhantom(baseline, e.targetName())
            : MountedIdentity.ofShadow(baseline, e.resolvedUuid(), e.targetName());

        // If currently shadowing something else, COMMIT the current target first.
        final MountDisposition oldDisp = MountDisposition.COMMIT;
        final boolean ok = kernel.swap(session, mount, oldDisp);
        if (!ok) {
            e.response().reply("<red>Shadow mount failed.</red>");
            return;
        }
        shadowReservations.put(Naming.normalizeKey(e.targetName()), e.actor());
        persistSession(e.actor(),
            baseline.isMain() ? null : baseline.profileUuid(),
            e.targetName(), e.resolvedUuid(), selfShadow,
            false, null, false, true, null);
        presentation.refreshForSession(session);
        e.response().reply("<green>Shadowing</green> <yellow>" + e.targetName() + "</yellow><green>.</green>");
    }

    private void onShadowLogout(final EngineEvent.ShadowLogout e) {
        final SessionState st = stateOf(e.actor());
        if (st != SessionState.SHADOW_MOUNTED) {
            e.response().reply("<red>No shadow session is active.</red>");
            return;
        }
        final DualPlayerSession session = registry.byController(e.actor()).orElseThrow();
        final SessionRecord sess = db.loadSession(e.actor()).orElseThrow();
        final ProfileIdentity baseline = baselineFromSession(e.actor(), sess);
        final String baseName = db.getAccountName(e.actor()).orElse("player");
        final String display = baseline.isMain() ? baseName
            : Naming.reconstructDisplayName(baseName, baseline.suffix());
        final MountedIdentity restored = MountedIdentity.ofBaseline(baseline, display);
        kernel.swap(session, restored, MountDisposition.COMMIT);
        clearShadowReservation(sess.shadowTargetName());
        persistSession(e.actor(),
            baseline.isMain() ? null : baseline.profileUuid(),
            null, null, false, false, null, false, true, null);
        presentation.refreshForSession(session);
        if (e.resetLocation()) {
            // Teleport the mounted (now baseline) body to its saved spawn.
            final Player p = Bukkit.getPlayer(e.actor());
            if (p != null && p.getRespawnLocation() != null) {
                p.teleport(p.getRespawnLocation());
            }
        }
        e.response().reply("<green>Shadow ended.</green>");
    }

    private void onShadowDiscard(final EngineEvent.ShadowDiscard e) {
        final SessionState st = stateOf(e.actor());
        if (st != SessionState.SHADOW_MOUNTED) {
            e.response().reply("<red>No shadow session is active.</red>");
            return;
        }
        final DualPlayerSession session = registry.byController(e.actor()).orElseThrow();
        final SessionRecord sess = db.loadSession(e.actor()).orElseThrow();
        final ProfileIdentity baseline = baselineFromSession(e.actor(), sess);
        final String baseName = db.getAccountName(e.actor()).orElse("player");
        final String display = baseline.isMain() ? baseName
            : Naming.reconstructDisplayName(baseName, baseline.suffix());
        final MountedIdentity restored = MountedIdentity.ofBaseline(baseline, display);
        kernel.swap(session, restored, MountDisposition.DISCARD);
        clearShadowReservation(sess.shadowTargetName());
        persistSession(e.actor(),
            baseline.isMain() ? null : baseline.profileUuid(),
            null, null, false, false, null, false, true, null);
        presentation.refreshForSession(session);
        e.response().reply("<green>Shadow discarded.</green>");
    }

    private void onShadowStatus(final EngineEvent.ShadowStatus e) {
        final SessionRecord sess = db.loadSession(e.actor()).orElse(null);
        if (sess == null || !sess.isShadowing()) { e.response().reply("<yellow>Not shadowing.</yellow>"); return; }
        e.response().reply("<gray>Shadowing</gray> <yellow>" + sess.shadowTargetName() + "</yellow>"
            + (sess.shadowSelf() ? " <gray>(self)</gray>" : ""));
    }

    // ────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ────────────────────────────────────────────────────────────────

    private void onControllerJoin(final EngineEvent.ControllerJoin e) {
        // §12: restore the persisted actual state exactly.
        final DualPlayerSession session = registry.byController(e.actor()).orElse(null);
        if (session == null) {
            log.warning("ControllerJoin for unknown session " + e.actor());
            return;
        }
        final SessionRecord sess = db.loadSession(e.actor()).orElse(null);
        if (sess == null) {
            // Fresh controller — main mount.
            final MountedIdentity mainMount = MountedIdentity.ofBaseline(
                ProfileIdentity.main(e.actor()), e.actorName());
            kernel.mount(session, mainMount);
            presentation.refreshForSession(session);
            return;
        }
        if (sess.conflictFrozen()) {
            // §12: restore the shell state.
            kernel.enterConflictShell(session);
            return;
        }
        final ProfileIdentity baseline = baselineFromSession(e.actor(), sess);
        final String baseName = db.getAccountName(e.actor()).orElse(e.actorName());
        if (sess.isShadowing() && sess.shadowTargetUuid() != null) {
            final MountedIdentity shadow = sess.shadowSelf()
                ? MountedIdentity.ofPhantom(baseline, sess.shadowTargetName())
                : MountedIdentity.ofShadow(baseline, sess.shadowTargetUuid(), sess.shadowTargetName());
            kernel.mount(session, shadow);
            shadowReservations.put(Naming.normalizeKey(sess.shadowTargetName()), e.actor());
        } else {
            final String display = baseline.isMain() ? baseName
                : Naming.reconstructDisplayName(baseName, baseline.suffix());
            kernel.mount(session, MountedIdentity.ofBaseline(baseline, display));
        }
        presentation.refreshForSession(session);
    }

    private void onControllerQuit(final EngineEvent.ControllerQuit e) {
        final DualPlayerSession session = registry.byController(e.actor()).orElse(null);
        if (session == null) return;
        final SessionRecord sess = db.loadSession(e.actor()).orElse(null);
        if (sess != null && sess.conflictFrozen()) {
            // §12: do not save the shell as canonical — keep deferred restore metadata.
            // Just unmount the (non-existent) mounted side and record nothing new.
        } else if (sess != null && sess.isShadowing()) {
            // §12: commit shadow changes on quit.
            kernel.persist(session);
            kernel.unmount(session, MountDisposition.COMMIT);
            // Keep the shadow reservation — on rejoin we reconstruct.
        } else {
            kernel.persist(session);
            kernel.unmount(session, MountDisposition.COMMIT);
        }
    }

    private void onShadowConflictArrived(final EngineEvent.ShadowConflictArrived e) {
        // §12: real target activates while another controller shadows that target.
        final DualPlayerSession session = registry.byController(e.actor()).orElse(null);
        if (session == null) return;
        final SettingsRecord settings = db.loadSettings(e.actor());
        final MountDisposition disp = settings.autoDiscard() ? MountDisposition.DISCARD : MountDisposition.COMMIT;
        // End shadow; enter conflict shell with deferred restore.
        final SessionRecord sess = db.loadSession(e.actor()).orElse(null);
        if (sess == null || !sess.isShadowing()) return;
        final ProfileIdentity baseline = baselineFromSession(e.actor(), sess);
        // Apply the disposition to the mounted-side via unmount, then enter shell.
        kernel.unmount(session, disp);
        clearShadowReservation(sess.shadowTargetName());
        kernel.enterConflictShell(session);
        persistSession(e.actor(),
            baseline.isMain() ? null : baseline.profileUuid(),
            null, null, false,
            true,
            baseline.isMain() ? null : baseline.profileUuid(),
            baseline.isMain(), settings.resetPositionOnRestore(),
            "real-target-activated");
        e.response().reply("<red>Shadow forced to end — the real account logged in.</red>");
    }

    private void onShadowConflictTargetLeft(final EngineEvent.ShadowConflictTargetLeft e) {
        // §12: keep shell state; clear spectator-follow; keep deferred restore pending.
        // With our spectator-shell implementation the controller is not camera-locked
        // to any target, so this is effectively a no-op.
    }

    private void onControllerGameModeChange(final EngineEvent.ControllerGameModeChange e) {
        // §13 / §12: if in shell and transitioning away from spectator, resolve the
        // deferred restore first, then re-apply the requested mode.
        final SessionState st = stateOf(e.actor());
        if (st != SessionState.CONFLICT_SPECTATOR_SHELL) return;
        if ("SPECTATOR".equalsIgnoreCase(e.toMode())) return;

        final DualPlayerSession session = registry.byController(e.actor()).orElseThrow();
        final SessionRecord sess = db.loadSession(e.actor()).orElseThrow();
        final ProfileIdentity baseline = baselineFromDeferred(e.actor(), sess);
        final String baseName = db.getAccountName(e.actor()).orElse("player");
        final String display = baseline.isMain() ? baseName
            : Naming.reconstructDisplayName(baseName, baseline.suffix());
        final MountedIdentity restored = MountedIdentity.ofBaseline(baseline, display);
        final GameMode requested = parseGameMode(e.toMode());
        kernel.resolveDeferredRestore(session, restored, sess.deferredRestoreResetPosition(), requested);
        persistSession(e.actor(),
            baseline.isMain() ? null : baseline.profileUuid(),
            null, null, false, false, null, false, true, null);
        presentation.refreshForSession(session);
    }

    // ────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────

    private ProfileIdentity baselineFromSession(final UUID controllerUuid, final SessionRecord sess) {
        if (sess == null || sess.activeProfileUuid() == null) {
            return ProfileIdentity.main(controllerUuid);
        }
        final Optional<ProfileRecord> p = db.findProfileByUuid(sess.activeProfileUuid());
        if (p.isEmpty()) return ProfileIdentity.main(controllerUuid);
        return ProfileIdentity.local(controllerUuid, p.get().profileUuid(), p.get().suffix());
    }

    private ProfileIdentity baselineFromDeferred(final UUID controllerUuid, final SessionRecord sess) {
        if (sess == null || sess.deferredRestoreMain() || sess.deferredRestoreProfileUuid() == null) {
            return ProfileIdentity.main(controllerUuid);
        }
        final Optional<ProfileRecord> p = db.findProfileByUuid(sess.deferredRestoreProfileUuid());
        if (p.isEmpty()) return ProfileIdentity.main(controllerUuid);
        return ProfileIdentity.local(controllerUuid, p.get().profileUuid(), p.get().suffix());
    }

    private void persistSession(final UUID controller, final UUID activeProfile,
                                final String shadowTargetName, final UUID shadowTargetUuid,
                                final boolean shadowSelf, final boolean conflictFrozen,
                                final UUID deferredRestoreProfile, final boolean deferredMain,
                                final boolean deferredResetPos, final String deferredReason) {
        if (activeProfile == null && shadowTargetName == null && !conflictFrozen) {
            db.clearSession(controller);
            return;
        }
        db.saveSession(new SessionRecord(controller, activeProfile, shadowTargetName, shadowTargetUuid,
            shadowSelf, conflictFrozen, deferredRestoreProfile, deferredMain, deferredResetPos, deferredReason));
    }

    private void clearShadowReservation(final String targetName) {
        if (targetName == null) return;
        shadowReservations.remove(Naming.normalizeKey(targetName));
    }

    public boolean isTargetReservedBy(final String name, final UUID controllerUuid) {
        final UUID res = shadowReservations.get(Naming.normalizeKey(name));
        return res != null && res.equals(controllerUuid);
    }

    public Optional<UUID> shadowReservationFor(final String name) {
        return Optional.ofNullable(shadowReservations.get(Naming.normalizeKey(name)));
    }

    private static GameMode parseGameMode(final String s) {
        try { return GameMode.valueOf(s.toUpperCase()); } catch (final RuntimeException ex) { return GameMode.SURVIVAL; }
    }
}
