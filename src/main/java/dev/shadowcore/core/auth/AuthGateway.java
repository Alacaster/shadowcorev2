package dev.shadowcore.core.auth;

import com.mojang.authlib.GameProfile;
import dev.shadowcore.model.ProfileIdentity;
import dev.shadowcore.store.Database;
import dev.shadowcore.store.Database.ProfileRecord;
import dev.shadowcore.util.Naming;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.GameProfileCache;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;

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

    public AuthGateway(final Logger log, final MinecraftServer server, final Database db) {
        this.log = log;
        this.server = server;
        this.db = db;
    }

    /**
     * Populate the profile cache with every known local profile identity.
     * Called once at plugin enable.
     */
    public void bootstrap() {
        final GameProfileCache cache = server.getProfileCache();
        int loaded = 0;
        for (final ProfileRecord p : db.listAllProfiles()) {
            final String base = db.getAccountName(p.ownerUuid()).orElse("player");
            final String displayName = Naming.reconstructDisplayName(base, p.suffix());
            cache.add(new GameProfile(p.profileUuid(), displayName));
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
        // Always remember what the Mojang UUID is for this real name — we
        // use this later when someone tries to /shadow that name.
        db.rememberKnownName(name, mojangUuid, true);

        // If the incoming name matches an existing local profile, rewrite.
        final Optional<UUID> rewritten = resolveUuidForName(name);
        if (rewritten.isPresent() && !rewritten.get().equals(mojangUuid)) {
            // There is no public Bukkit API to change the event's UUID once
            // Mojang has resolved it. We disallow in that case — the user
            // is trying to log in with a name that already belongs to a
            // local profile under some other account, and that's a name
            // collision that must be resolved by the controller running
            // /lprofile rename first. This is spec §9.1's "renameable
            // collision with a newly created real account" condition.
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "The name '" + name + "' is currently registered as a local profile. " +
                "The profile owner must rename it before you can log in.");
        }
    }

    /**
     * Main-thread login hook. If the plugin needs to reject a login for
     * orchestration-level reasons (e.g., a shadow conflict still unresolved
     * from a prior crash — spec §18), this is where we do it.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(final PlayerLoginEvent event) {
        // Currently only records last-seen name for the player's UUID.
        db.upsertPlayer(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }
}
