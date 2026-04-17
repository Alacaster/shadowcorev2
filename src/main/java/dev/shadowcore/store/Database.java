package dev.shadowcore.store;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * SQLite-backed orchestration store.
 *
 * <p><b>Spec §5 — orchestration only.</b> This store MUST NOT contain
 * inventory values, location values, health values, ender-pearl payloads,
 * pet payloads, or any other gameplay payload. Every column and every
 * insert in this class is audited against that rule.</p>
 *
 * <p>Tables:</p>
 * <ul>
 *   <li>{@code players} — controller account metadata (UUID, last-seen name, profile limit).</li>
 *   <li>{@code profiles} — local profile metadata (UUID, owner UUID, suffix).</li>
 *   <li>{@code synthetics} — synthetic identity registry for shadow targets whose name is not a real account.</li>
 *   <li>{@code sessions} — orchestration actual-state (active profile, shadow target, conflict frozen, deferred restore metadata).</li>
 *   <li>{@code settings} — per-controller policy settings (auto-discard, reset-position).</li>
 *   <li>{@code known_names} — cached Mojang name↔UUID bindings (so /shadow on a never-joined real account can resolve).</li>
 * </ul>
 */
public final class Database implements AutoCloseable {
    private final Connection connection;
    private final Logger log;

    public Database(final File pluginFolder, final Logger log) {
        this.log = log;
        try {
            final File dbFile = new File(pluginFolder, "shadowcore.db");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (final Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA busy_timeout=3000");
            }
            migrateLegacyTables();
            createSchema();
        } catch (final SQLException e) {
            throw new IllegalStateException("Failed to open SQLite orchestration store", e);
        }
    }

    /**
     * One-shot migration from pre-3.0 schema (columns named {@code base_uuid},
     * {@code owner_uuid}, shadowcore-2 table names) to the 3.0 layout. The
     * approach is conservative: we detect legacy tables by schema, rename them
     * aside with an {@code _legacy3} suffix, and let {@link #createSchema} run
     * fresh. Operators who need to preserve the old state can {@code INSERT
     * INTO players SELECT base_uuid, account_name, ... FROM players_legacy3}
     * manually; automatic data copy is intentionally not done because some
     * column semantics changed (shadow-reservation rebuild logic is now
     * different), and it's safer to migrate by replay than by DML.
     */
    private void migrateLegacyTables() throws SQLException {
        // Detect: legacy players table has a `base_uuid` column.
        if (!tableHasColumn("players", "base_uuid")) return;
        log.warning("ShadowCore: pre-3.0 database schema detected. Renaming legacy tables aside "
            + "(*_legacy3) and creating fresh 3.0 tables. Your old data is preserved under the "
            + "legacy names. See docs for manual migration.");
        try (final Statement s = connection.createStatement()) {
            for (final String t : new String[]{"players", "profiles", "sessions", "player_settings", "known_names"}) {
                if (tableExists(t)) {
                    s.executeUpdate("ALTER TABLE " + t + " RENAME TO " + t + "_legacy3");
                }
            }
        }
    }

    private boolean tableExists(final String name) throws SQLException {
        try (final PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name = ?")) {
            ps.setString(1, name);
            try (final ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean tableHasColumn(final String table, final String column) throws SQLException {
        if (!tableExists(table)) return false;
        try (final Statement s = connection.createStatement();
             final ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    private void createSchema() throws SQLException {
        try (final Statement s = connection.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS players (
                    controller_uuid TEXT PRIMARY KEY,
                    account_name    TEXT NOT NULL,
                    max_profiles    INTEGER NOT NULL DEFAULT 2,
                    first_seen_at   INTEGER NOT NULL,
                    last_seen_at    INTEGER NOT NULL
                )
            """);
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_name ON players(account_name COLLATE NOCASE)");

            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS profiles (
                    profile_uuid     TEXT PRIMARY KEY,
                    owner_uuid       TEXT NOT NULL,
                    suffix           TEXT NOT NULL,
                    created_at       INTEGER NOT NULL,
                    last_mounted_at  INTEGER NOT NULL DEFAULT 0,
                    renameable       INTEGER NOT NULL DEFAULT 1,
                    UNIQUE(owner_uuid, suffix COLLATE NOCASE)
                )
            """);
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_profiles_owner ON profiles(owner_uuid)");

            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS synthetics (
                    synthetic_uuid  TEXT PRIMARY KEY,
                    target_name     TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    created_at      INTEGER NOT NULL
                )
            """);

            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    controller_uuid                 TEXT PRIMARY KEY,
                    active_profile_uuid             TEXT,
                    shadow_target_name              TEXT,
                    shadow_target_uuid              TEXT,
                    shadow_self                     INTEGER NOT NULL DEFAULT 0,
                    conflict_frozen                 INTEGER NOT NULL DEFAULT 0,
                    deferred_restore_profile_uuid   TEXT,
                    deferred_restore_main           INTEGER NOT NULL DEFAULT 0,
                    deferred_restore_reset_position INTEGER NOT NULL DEFAULT 1,
                    deferred_restore_reason         TEXT
                )
            """);

            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS settings (
                    controller_uuid            TEXT PRIMARY KEY,
                    shadow_conflict_discard    INTEGER NOT NULL DEFAULT 1,
                    shadow_conflict_reset_pos  INTEGER NOT NULL DEFAULT 1
                )
            """);

            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS known_names (
                    name_lower   TEXT PRIMARY KEY COLLATE NOCASE,
                    mojang_uuid  TEXT,
                    is_real      INTEGER NOT NULL,
                    resolved_at  INTEGER NOT NULL
                )
            """);
        }
    }

    @Override public void close() {
        try { connection.close(); } catch (final SQLException e) { log.warning("DB close failed: " + e.getMessage()); }
    }

    // ────────────────────────────────────────────────────────────────
    //  Players (controller account metadata)
    // ────────────────────────────────────────────────────────────────

    public void upsertPlayer(final UUID controllerUuid, final String accountName) {
        final long now = System.currentTimeMillis();
        try (final PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO players (controller_uuid, account_name, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(controller_uuid) DO UPDATE SET
                    account_name = excluded.account_name,
                    last_seen_at = excluded.last_seen_at
            """)) {
            ps.setString(1, controllerUuid.toString());
            ps.setString(2, accountName);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
        } catch (final SQLException e) { log.warning("upsertPlayer: " + e.getMessage()); }
    }

    public Optional<String> getAccountName(final UUID controllerUuid) {
        try (final PreparedStatement ps = connection.prepareStatement(
                "SELECT account_name FROM players WHERE controller_uuid = ?")) {
            ps.setString(1, controllerUuid.toString());
            try (final ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString(1));
            }
        } catch (final SQLException e) { log.warning("getAccountName: " + e.getMessage()); }
        return Optional.empty();
    }

    public int getMaxProfiles(final UUID controllerUuid, final int defaultLimit) {
        try (final PreparedStatement ps = connection.prepareStatement(
                "SELECT max_profiles FROM players WHERE controller_uuid = ?")) {
            ps.setString(1, controllerUuid.toString());
            try (final ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (final SQLException e) { log.warning("getMaxProfiles: " + e.getMessage()); }
        return defaultLimit;
    }

    public void setMaxProfiles(final UUID controllerUuid, final int limit) {
        try (final PreparedStatement ps = connection.prepareStatement(
                "UPDATE players SET max_profiles = ? WHERE controller_uuid = ?")) {
            ps.setInt(1, limit);
            ps.setString(2, controllerUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) { log.warning("setMaxProfiles: " + e.getMessage()); }
    }

    // ────────────────────────────────────────────────────────────────
    //  Profiles (local-profile suffix metadata)
    // ────────────────────────────────────────────────────────────────

    public record ProfileRecord(UUID profileUuid, UUID ownerUuid, String suffix, long createdAt, long lastMountedAt, boolean renameable) {}

    public void insertProfile(final ProfileRecord r) {
        try (final PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO profiles (profile_uuid, owner_uuid, suffix, created_at, last_mounted_at, renameable)
                VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            ps.setString(1, r.profileUuid().toString());
            ps.setString(2, r.ownerUuid().toString());
            ps.setString(3, r.suffix());
            ps.setLong(4, r.createdAt());
            ps.setLong(5, r.lastMountedAt());
            ps.setInt(6, r.renameable() ? 1 : 0);
            ps.executeUpdate();
        } catch (final SQLException e) { log.warning("insertProfile: " + e.getMessage()); }
    }

    public Optional<ProfileRecord> findProfileBySuffix(final UUID ownerUuid, final String suffix) {
        try (final PreparedStatement ps = connection.prepareStatement("""
                SELECT profile_uuid, owner_uuid, suffix, created_at, last_mounted_at, renameable
                FROM profiles WHERE owner_uuid = ? AND suffix = ? COLLATE NOCASE
            """)) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, suffix);
            try (final ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(readProfile(rs));
            }
        } catch (final SQLException e) { log.warning("findProfileBySuffix: " + e.getMessage()); }
        return Optional.empty();
    }

    public Optional<ProfileRecord> findProfileByUuid(final UUID profileUuid) {
        try (final PreparedStatement ps = connection.prepareStatement("""
                SELECT profile_uuid, owner_uuid, suffix, created_at, last_mounted_at, renameable
                FROM profiles WHERE profile_uuid = ?
            """)) {
            ps.setString(1, profileUuid.toString());
            try (final ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(readProfile(rs));
            }
        } catch (final SQLException e) { log.warning("findProfileByUuid: " + e.getMessage()); }
        return Optional.empty();
    }

    public List<ProfileRecord> listProfiles(final UUID ownerUuid) {
        final List<ProfileRecord> out = new ArrayList<>();
        try (final PreparedStatement ps = connection.prepareStatement("""
                SELECT profile_uuid, owner_uuid, suffix, created_at, last_mounted_at, renameable
                FROM profiles WHERE owner_uuid = ? ORDER BY created_at
            """)) {
            ps.setString(1, ownerUuid.toString());
            try (final ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readProfile(rs));
            }
        } catch (final SQLException e) { log.warning("listProfiles: " + e.getMessage()); }
        return out;
    }

    public List<ProfileRecord> listAllProfiles() {
        final List<ProfileRecord> out = new ArrayList<>();
        try (final Statement s = connection.createStatement();
             final ResultSet rs = s.executeQuery("""
                SELECT profile_uuid, owner_uuid, suffix, created_at, last_mounted_at, renameable
                FROM profiles
             """)) {
            while (rs.next()) out.add(readProfile(rs));
        } catch (final SQLException e) { log.warning("listAllProfiles: " + e.getMessage()); }
        return out;
    }

    /**
     * Candidate lookup used by {@link dev.shadowcore.core.auth.AuthGateway}:
     * find profiles whose owner's account_name is a prefix of the given
     * display-name search string. This narrows the candidate set so we don't
     * have to reconstruct every profile's display name on every login.
     */
    public List<ProfileRecord> findProfilesByLikelyDisplay(final String displayLower) {
        final List<ProfileRecord> out = new ArrayList<>();
        final int underscore = displayLower.indexOf('_');
        if (underscore < 0) return out;
        // The reconstructed name is always "<basePrefix>_<suffix>" where the
        // base prefix may be truncated. So the suffix part is *always* after
        // the final underscore and ≤10 chars. Query by suffix to narrow.
        final String suffixGuess = displayLower.substring(displayLower.lastIndexOf('_') + 1);
        try (final PreparedStatement ps = connection.prepareStatement("""
                SELECT profile_uuid, owner_uuid, suffix, created_at, last_mounted_at, renameable
                FROM profiles WHERE suffix = ? COLLATE NOCASE
            """)) {
            ps.setString(1, suffixGuess);
            try (final ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readProfile(rs));
            }
        } catch (final SQLException e) { log.warning("findProfilesByLikelyDisplay: " + e.getMessage()); }
        return out;
    }

    public void updateProfileSuffix(final UUID profileUuid, final String newSuffix) {
        try (final PreparedStatement ps = connection.prepareStatement(
                "UPDATE profiles SET suffix = ? WHERE profile_uuid = ?")) {
            ps.setString(1, newSuffix);
            ps.setString(2, profileUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) { log.warning("updateProfileSuffix: " + e.getMessage()); }
    }

    public void touchProfileMounted(final UUID profileUuid) {
        try (final PreparedStatement ps = connection.prepareStatement(
                "UPDATE profiles SET last_mounted_at = ? WHERE profile_uuid = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, profileUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) { log.warning("touchProfileMounted: " + e.getMessage()); }
    }

    public void deleteProfile(final UUID profileUuid) {
        try (final PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM profiles WHERE profile_uuid = ?")) {
            ps.setString(1, profileUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) { log.warning("deleteProfile: " + e.getMessage()); }
    }

    private ProfileRecord readProfile(final ResultSet rs) throws SQLException {
        return new ProfileRecord(
            UUID.fromString(rs.getString("profile_uuid")),
            UUID.fromString(rs.getString("owner_uuid")),
            rs.getString("suffix"),
            rs.getLong("created_at"),
            rs.getLong("last_mounted_at"),
            rs.getInt("renameable") != 0
        );
    }

    // ────────────────────────────────────────────────────────────────
    //  Synthetics (shadow-target identities not backed by a real account)
    // ────────────────────────────────────────────────────────────────

    public UUID getOrCreateSyntheticForName(final String targetName) {
        final String lower = targetName.toLowerCase(Locale.ROOT);
        try (final PreparedStatement ps = connection.prepareStatement(
                "SELECT synthetic_uuid FROM synthetics WHERE target_name = ? COLLATE NOCASE")) {
            ps.setString(1, lower);
            try (final ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString(1));
            }
        } catch (final SQLException e) { log.warning("getOrCreateSynthetic(select): " + e.getMessage()); }
        final UUID fresh = UUID.randomUUID();
        try (final PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO synthetics (synthetic_uuid, target_name, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, fresh.toString());
            ps.setString(2, lower);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (final SQLException e) { log.warning("getOrCreateSynthetic(insert): " + e.getMessage()); }
        return fresh;
    }

    public Optional<UUID> resolveSyntheticByName(final String nameLower) {
        try (final PreparedStatement ps = connection.prepareStatement(
                "SELECT synthetic_uuid FROM synthetics WHERE target_name = ? COLLATE NOCASE")) {
            ps.setString(1, nameLower);
            try (final ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(UUID.fromString(rs.getString(1)));
            }
        } catch (final SQLException e) { log.warning("resolveSyntheticByName: " + e.getMessage()); }
        return Optional.empty();
    }

    public Optional<String> resolveSyntheticNameByUuid(final UUID uuid) {
        try (final PreparedStatement ps = connection.prepareStatement(
                "SELECT target_name FROM synthetics WHERE synthetic_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (final ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getString(1));
            }
        } catch (final SQLException e) { log.warning("resolveSyntheticNameByUuid: " + e.getMessage()); }
        return Optional.empty();
    }

    // ────────────────────────────────────────────────────────────────
    //  Session orchestration state
    // ────────────────────────────────────────────────────────────────

    public record SessionRecord(
        UUID controllerUuid,
        UUID activeProfileUuid,        // null = main
        String shadowTargetName,       // null = not shadowing
        UUID shadowTargetUuid,         // null unless shadowing
        boolean shadowSelf,
        boolean conflictFrozen,
        UUID deferredRestoreProfileUuid,  // null unless deferred restore is active
        boolean deferredRestoreMain,
        boolean deferredRestoreResetPosition,
        String deferredRestoreReason
    ) {
        public boolean isShadowing() { return shadowTargetName != null; }
        public boolean isMain() { return activeProfileUuid == null && shadowTargetName == null; }
        public boolean isLocalProfile() { return activeProfileUuid != null && shadowTargetName == null; }
    }

    public Optional<SessionRecord> loadSession(final UUID controllerUuid) {
        try (final PreparedStatement ps = connection.prepareStatement("""
                SELECT controller_uuid, active_profile_uuid, shadow_target_name, shadow_target_uuid,
                       shadow_self, conflict_frozen, deferred_restore_profile_uuid,
                       deferred_restore_main, deferred_restore_reset_position, deferred_restore_reason
                FROM sessions WHERE controller_uuid = ?
            """)) {
            ps.setString(1, controllerUuid.toString());
            try (final ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(readSession(rs));
            }
        } catch (final SQLException e) { log.warning("loadSession: " + e.getMessage()); }
        return Optional.empty();
    }

    public List<SessionRecord> loadAllSessions() {
        final List<SessionRecord> out = new ArrayList<>();
        try (final Statement s = connection.createStatement();
             final ResultSet rs = s.executeQuery("""
                SELECT controller_uuid, active_profile_uuid, shadow_target_name, shadow_target_uuid,
                       shadow_self, conflict_frozen, deferred_restore_profile_uuid,
                       deferred_restore_main, deferred_restore_reset_position, deferred_restore_reason
                FROM sessions
             """)) {
            while (rs.next()) out.add(readSession(rs));
        } catch (final SQLException e) { log.warning("loadAllSessions: " + e.getMessage()); }
        return out;
    }

    public void saveSession(final SessionRecord r) {
        try (final PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO sessions (controller_uuid, active_profile_uuid, shadow_target_name, shadow_target_uuid,
                                      shadow_self, conflict_frozen, deferred_restore_profile_uuid,
                                      deferred_restore_main, deferred_restore_reset_position, deferred_restore_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(controller_uuid) DO UPDATE SET
                    active_profile_uuid = excluded.active_profile_uuid,
                    shadow_target_name = excluded.shadow_target_name,
                    shadow_target_uuid = excluded.shadow_target_uuid,
                    shadow_self = excluded.shadow_self,
                    conflict_frozen = excluded.conflict_frozen,
                    deferred_restore_profile_uuid = excluded.deferred_restore_profile_uuid,
                    deferred_restore_main = excluded.deferred_restore_main,
                    deferred_restore_reset_position = excluded.deferred_restore_reset_position,
                    deferred_restore_reason = excluded.deferred_restore_reason
            """)) {
            ps.setString(1, r.controllerUuid().toString());
            setNullableUuid(ps, 2, r.activeProfileUuid());
            ps.setString(3, r.shadowTargetName());
            setNullableUuid(ps, 4, r.shadowTargetUuid());
            ps.setInt(5, r.shadowSelf() ? 1 : 0);
            ps.setInt(6, r.conflictFrozen() ? 1 : 0);
            setNullableUuid(ps, 7, r.deferredRestoreProfileUuid());
            ps.setInt(8, r.deferredRestoreMain() ? 1 : 0);
            ps.setInt(9, r.deferredRestoreResetPosition() ? 1 : 0);
            ps.setString(10, r.deferredRestoreReason());
            ps.executeUpdate();
        } catch (final SQLException e) { log.warning("saveSession: " + e.getMessage()); }
    }

    public void clearSession(final UUID controllerUuid) {
        try (final PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM sessions WHERE controller_uuid = ?")) {
            ps.setString(1, controllerUuid.toString());
            ps.executeUpdate();
        } catch (final SQLException e) { log.warning("clearSession: " + e.getMessage()); }
    }

    private SessionRecord readSession(final ResultSet rs) throws SQLException {
        return new SessionRecord(
            UUID.fromString(rs.getString("controller_uuid")),
            getNullableUuid(rs, "active_profile_uuid"),
            rs.getString("shadow_target_name"),
            getNullableUuid(rs, "shadow_target_uuid"),
            rs.getInt("shadow_self") != 0,
            rs.getInt("conflict_frozen") != 0,
            getNullableUuid(rs, "deferred_restore_profile_uuid"),
            rs.getInt("deferred_restore_main") != 0,
            rs.getInt("deferred_restore_reset_position") != 0,
            rs.getString("deferred_restore_reason")
        );
    }

    private static void setNullableUuid(final PreparedStatement ps, final int idx, final UUID u) throws SQLException {
        if (u == null) ps.setNull(idx, java.sql.Types.VARCHAR);
        else ps.setString(idx, u.toString());
    }
    private static UUID getNullableUuid(final ResultSet rs, final String col) throws SQLException {
        final String s = rs.getString(col);
        return s == null ? null : UUID.fromString(s);
    }

    // ────────────────────────────────────────────────────────────────
    //  Settings
    // ────────────────────────────────────────────────────────────────

    public record SettingsRecord(boolean autoDiscard, boolean resetPositionOnRestore) {}

    public SettingsRecord loadSettings(final UUID controllerUuid) {
        try (final PreparedStatement ps = connection.prepareStatement(
                "SELECT shadow_conflict_discard, shadow_conflict_reset_pos FROM settings WHERE controller_uuid = ?")) {
            ps.setString(1, controllerUuid.toString());
            try (final ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new SettingsRecord(rs.getInt(1) != 0, rs.getInt(2) != 0);
            }
        } catch (final SQLException e) { log.warning("loadSettings: " + e.getMessage()); }
        return new SettingsRecord(true, true); // spec §13 defaults
    }

    public void saveSettings(final UUID controllerUuid, final SettingsRecord r) {
        try (final PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO settings (controller_uuid, shadow_conflict_discard, shadow_conflict_reset_pos)
                VALUES (?, ?, ?)
                ON CONFLICT(controller_uuid) DO UPDATE SET
                    shadow_conflict_discard = excluded.shadow_conflict_discard,
                    shadow_conflict_reset_pos = excluded.shadow_conflict_reset_pos
            """)) {
            ps.setString(1, controllerUuid.toString());
            ps.setInt(2, r.autoDiscard() ? 1 : 0);
            ps.setInt(3, r.resetPositionOnRestore() ? 1 : 0);
            ps.executeUpdate();
        } catch (final SQLException e) { log.warning("saveSettings: " + e.getMessage()); }
    }

    // ────────────────────────────────────────────────────────────────
    //  Known names (name↔Mojang UUID cache)
    // ────────────────────────────────────────────────────────────────

    public record KnownName(String name, UUID mojangUuid, boolean isReal, long resolvedAt) {}

    public void rememberKnownName(final String name, final UUID mojangUuid, final boolean isReal) {
        final String lower = name.toLowerCase(Locale.ROOT);
        try (final PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO known_names (name_lower, mojang_uuid, is_real, resolved_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(name_lower) DO UPDATE SET
                    mojang_uuid = excluded.mojang_uuid,
                    is_real = excluded.is_real,
                    resolved_at = excluded.resolved_at
            """)) {
            ps.setString(1, lower);
            ps.setString(2, mojangUuid == null ? null : mojangUuid.toString());
            ps.setInt(3, isReal ? 1 : 0);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (final SQLException e) { log.warning("rememberKnownName: " + e.getMessage()); }
    }

    public Optional<KnownName> lookupKnownName(final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        try (final PreparedStatement ps = connection.prepareStatement(
                "SELECT name_lower, mojang_uuid, is_real, resolved_at FROM known_names WHERE name_lower = ?")) {
            ps.setString(1, lower);
            try (final ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    final String u = rs.getString(2);
                    return Optional.of(new KnownName(
                        rs.getString(1),
                        u == null ? null : UUID.fromString(u),
                        rs.getInt(3) != 0,
                        rs.getLong(4)
                    ));
                }
            }
        } catch (final SQLException e) { log.warning("lookupKnownName: " + e.getMessage()); }
        return Optional.empty();
    }
}
