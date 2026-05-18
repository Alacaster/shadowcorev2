package dev.shadowcore.core.auth;

import com.mojang.authlib.GameProfile;
import dev.shadowcore.core.nms.NmsCompat;
import dev.shadowcore.model.ProfileIdentity;
import dev.shadowcore.store.Database;
import dev.shadowcore.store.Database.ProfileRecord;
import dev.shadowcore.util.Naming;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Login interception and name-resolution authority.
 *
 * <p>Spec Rule 2.22: "The mod will take full control over server
 * authentication, pass through normal auth, provide name resolution to
 * plugins for alt profiles."</p>
 *
 * <h2>What this class does</h2>
 * <ol>
 *   <li><b>Pre-login name resolution.</b> When a client tries to join with a
 *       name that matches {@code <baseAccount>_<suffix>} — i.e., the
 *       reconstructed display name of some local profile — we rewrite the
 *       pre-login UUID/name so the server accepts them as the profile's
 *       synthetic identity. This is necessary for the (rare) alt-login path
 *       where the human deliberately logs in as their local profile from a
 *       different client.</li>
 *
 *   <li><b>GameProfileCache pre-population.</b> Every local profile UUID is
 *       inserted into the vanilla profile cache at plugin enable time with
 *       its reconstructed display name. This makes {@code
 *       server.getProfileCache().getProfile(uuid)} and {@code .getProfile(name)}
 *       both succeed for local profiles, without us having to patch the
 *       vanilla cache.</li>
 *
 *   <li><b>Bukkit-level name resolution.</b> {@link #resolveUuidForName} and
 *       {@link #resolveNameForUuid} are the public accessors the rest of the
 *       plugin uses. Vanilla {@code OfflinePlayer} lookups will first hit
 *       the GameProfileCache (which we pre-populate) and so pick up the
 *       right identity without further interception.</li>
 * </ol>
 *
 * <h2>What this class deliberately does NOT do</h2>
 * We do not replace vanilla's LoginPacketListenerImpl nor patch the
 * encryption/authentication path. Those are correct for real Mojang logins;
 * we don't need to alter them. The "auth takeover" in spec Rule 2.22 is
 * about <em>outcome</em> (local profiles behave like accounts for name
 * resolution purposes), not about bypassing Mojang session verification.
 */
public final class AuthGateway implements Listener {
    private final Logger log;
    private final MinecraftServer server;
    private final Database db;
    private dev.shadowcore.core.swap.PendingSwapRegistry pendingSwaps; // optional; set after construction

    public AuthGateway(final Logger log, final MinecraftServer server, final Database db) {
        this.log = log;
        this.server = server;
        this.db = db;
    }

    /**
     * Wire up the pending-swap registry after construction. The registry is
     * needed during {@link #onAsyncPreLogin} to detect transfer-reconnects
     * that should be placed on a non-main identity. If never set (skeleton
     * phase), pre-login falls through to normal resolution.
     */
    public void attachPendingSwaps(final dev.shadowcore.core.swap.PendingSwapRegistry pendingSwaps) {
        this.pendingSwaps = pendingSwaps;
    }

    /**
     * Populate the profile cache with every known local profile identity.
     * Called once at plugin enable.
     */
    public void bootstrap() {
        final Object cache = NmsCompat.getProfileCache(server);
        if (cache == null) {
            log.warning("AuthGateway: profile cache unavailable — local profile names will not pre-resolve.");
            return;
        }
        int loaded = 0;
        for (final ProfileRecord p : db.listAllProfiles()) {
            final String base = db.getAccountName(p.ownerUuid()).orElse("player");
            final String displayName = Naming.reconstructDisplayName(base, p.suffix());
            NmsCompat.addToProfileCache(cache, new GameProfile(p.profileUuid(), displayName));
            loaded++;
        }
        if (loaded > 0) log.info("AuthGateway: pre-populated " + loaded + " local profile names into the GameProfileCache.");
    }

    /**
     * Resolve a display name to the UUID the server should treat it as.
     * Authoritative order:
     * <ol>
     *   <li>A local profile whose reconstructed display name matches exactly.</li>
     *   <li>A synthetic identity previously bound to this name.</li>
     *   <li>The real Mojang UUID (falling through to vanilla).</li>
     * </ol>
     */
    public Optional<UUID> resolveUuidForName(final String name) {
        if (name == null) return Optional.empty();
        final String lower = Naming.normalizeKey(name);
        // Pass 1: local profiles. We iterate because the stored value is a
        // suffix, not the expanded name — we have to reconstruct each one
        // to compare. Cost is O(profiles-for-owners-with-matching-prefix);
        // the DB has an index on account_name so we can narrow.
        for (final ProfileRecord p : db.findProfilesByLikelyDisplay(lower)) {
            final String base = db.getAccountName(p.ownerUuid()).orElse(null);
            if (base == null) continue;
            final String expanded = Naming.reconstructDisplayName(base, p.suffix());
            if (Naming.normalizeKey(expanded).equals(lower)) {
                return Optional.of(p.profileUuid());
            }
        }
        // Pass 2: synthetic identities created for shadow targets whose name
        // was not a real Mojang account.
        return db.resolveSyntheticByName(lower);
    }

    /**
     * Reverse resolution: the display name for a given UUID, regardless of
     * whether it is a real account, a local profile, or a synthetic
     * identity. For local profiles, this reconstructs the name fresh from
     * the current controller account name (sync spec §2.5).
     */
    public Optional<String> resolveNameForUuid(final UUID uuid) {
        if (uuid == null) return Optional.empty();
        final Optional<ProfileRecord> profile = db.findProfileByUuid(uuid);
        if (profile.isPresent()) {
            final ProfileRecord p = profile.get();
            final String base = db.getAccountName(p.ownerUuid()).orElse("player");
            return Optional.of(Naming.reconstructDisplayName(base, p.suffix()));
        }
        return db.resolveSyntheticNameByUuid(uuid);
    }

    /**
     * Given the UUID of a Bukkit Player object, return the Mojang UUID of
     * the human controller behind that player. If the input UUID is a
     * known local profile, the profile's owner UUID is returned. Otherwise
     * the input UUID is returned unchanged (it's already a Mojang UUID,
     * either a real Mojang account or a synthetic one we created).
     *
     * <p>Sessions are keyed on the controller's Mojang UUID, never on the
     * profile UUID, so any code path that submits engine events from a
     * Bukkit Player must call this first to get the right session key.</p>
     */
    public UUID resolveControllerMojangUuid(final UUID joiningUuid) {
        if (joiningUuid == null) return null;
        try {
            return db.findProfileByUuid(joiningUuid)
                .map(p -> p.ownerUuid())
                .orElse(joiningUuid);
        } catch (final Exception ex) {
            dev.shadowcore.util.Diag.warn(log, "auth",
                "resolveControllerMojangUuid failed for " + joiningUuid
                + ": " + ex.getMessage() + " — falling back to joining UUID");
            return joiningUuid;
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Bukkit event hooks — pre-login rewriting.
    // ────────────────────────────────────────────────────────────────

    /**
     * Pre-login hook. If the client's claimed name matches a known local
     * profile, rewrite the UUID so the server creates the session against
     * the profile's synthetic identity rather than the Mojang UUID of a
     * player account that happens to share that name (there wouldn't be
     * one, since the name contains an underscore, but we defend anyway).
     *
     * <p>Note: Bukkit's {@link AsyncPlayerPreLoginEvent} happens <em>after</em>
     * Mojang session verification, which is why we can safely rewrite
     * here without breaking online-mode. We also have access to the
     * Mojang-resolved UUID at this point, which we save into the known-names
     * cache for future shadow-target resolution.</p>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLogin(final AsyncPlayerPreLoginEvent event) {
        final String name = event.getName();
        final UUID mojangUuid = event.getUniqueId();
        dev.shadowcore.util.Diag.info(log, "auth",
            "onAsyncPreLogin: name=" + name + " mojangUuid=" + mojangUuid
            + (isTransferSafe(event) ? " [transferred]" : ""));
        // Always remember what the Mojang UUID is for this real name — we
        // use this later when someone tries to /shadow that name.
        db.rememberKnownName(name, mojangUuid, true);
        // Also record last-seen name for this UUID. Previously done in a
        // PlayerLoginEvent handler, but Paper's HorriblePlayerLoginEventHack
        // disables the reconfigure API whenever PlayerLoginEvent has any
        // listeners. Doing the upsert here is equivalent for our purposes.
        db.upsertPlayer(mojangUuid, name);

        // Pending-swap rewrite. If this login is a transfer-reconnect that
        // ShadowCore initiated as part of a profile switch, we have a
        // record in the pending-swap registry keyed on the Mojang UUID.
        // Rewrite the event's profile to the target identity's UUID and
        // name so the subsequent ServerPlayer construction picks up the
        // correct identity.
        if (pendingSwaps != null) {
            final Optional<dev.shadowcore.core.swap.PendingSwapRegistry.Pending> pending =
                pendingSwaps.poll(mojangUuid);
            if (pending.isPresent()) {
                final dev.shadowcore.model.MountedIdentity target = pending.get().targetIdentity();
                dev.shadowcore.util.Diag.info(log, "auth",
                    "onAsyncPreLogin: consuming pending swap -> "
                    + target.kind() + ":" + target.displayName() + " uuid=" + target.uuid());
                try {
                    final com.destroystokyo.paper.profile.PlayerProfile rewritten =
                        org.bukkit.Bukkit.createProfile(target.uuid(), target.displayName());
                    event.setPlayerProfile(rewritten);
                    // Early-return: the rest of this handler's collision
                    // check is irrelevant for a deliberate swap.
                    return;
                } catch (final RuntimeException ex) {
                    dev.shadowcore.util.Diag.error(log, "auth",
                        "onAsyncPreLogin: setPlayerProfile failed for pending swap of "
                        + mojangUuid + "; login will continue as the Mojang identity", ex);
                }
            }
        }

        // If the incoming name matches an existing local profile, disallow —
        // but ONLY if the profile doesn't belong to the logging-in person.
        // If FirstMage (the real Mojang account) is logging in, and the
        // profile 'FirstMage' exists in our DB, and that profile's owner
        // is 3d263e2c... (the Mojang UUID of the person logging in), it is
        // not a collision; it's the profile owner logging into their own
        // main account. Only kick when someone ELSE is trying to take a
        // name registered to another controller's profile.
        final Optional<UUID> rewritten = resolveUuidForName(name);
        if (rewritten.isPresent() && !rewritten.get().equals(mojangUuid)) {
            // Is the resolved UUID a profile owned by the logging-in person?
            final Optional<Database.ProfileRecord> profile = db.findProfileByUuid(rewritten.get());
            if (profile.isPresent() && profile.get().ownerUuid().equals(mojangUuid)) {
                dev.shadowcore.util.Diag.trace(log, "auth",
                    "onAsyncPreLogin: name '" + name + "' resolves to profile UUID="
                    + rewritten.get() + " but profile owner is the incoming Mojang UUID — not a collision");
            } else {
                dev.shadowcore.util.Diag.warn(log, "auth",
                    "onAsyncPreLogin DISALLOW: name '" + name + "' is a local profile UUID="
                    + rewritten.get() + " (owner=" + profile.map(p -> p.ownerUuid().toString()).orElse("?")
                    + ") but incoming Mojang UUID=" + mojangUuid + " — name collision");
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "The name '" + name + "' is currently registered as a local profile. " +
                    "The profile owner must rename it before you can log in.");
                return;
            }
        }
        dev.shadowcore.util.Diag.trace(log, "auth",
            "onAsyncPreLogin OK: no collision for " + name);
    }

    /**
     * Return {@code event.isTransferred()} if the API is available in this
     * Paper version; otherwise false. Defensive because the method was
     * added in Paper 1.20.5 and we want to compile cleanly even if the
     * event API shifts.
     */
    private static boolean isTransferSafe(final AsyncPlayerPreLoginEvent event) {
        try {
            return event.isTransferred();
        } catch (final NoSuchMethodError ignored) {
            return false;
        } catch (final RuntimeException ignored) {
            return false;
        }
    }
}
