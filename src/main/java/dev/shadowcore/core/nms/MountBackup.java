package dev.shadowcore.core.nms;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;

/**
 * Pre-mount .dat backups used to implement {@link
 * dev.shadowcore.model.MountDisposition#DISCARD}.
 *
 * <p>Vanilla's {@link net.minecraft.server.players.PlayerList#remove} always
 * runs {@code save()} on the player it is removing. For COMMIT disposition
 * that is exactly what we want. For DISCARD we need to <em>undo</em> that
 * save. The simplest way is:</p>
 *
 * <ol>
 *   <li>Before mount: copy {@code playerdata/<uuid>.dat} to a shadowcore
 *       backup folder (if the .dat doesn't exist yet, record that fact so
 *       restore knows to delete rather than copy).</li>
 *   <li>Mount runs; player makes changes; vanilla save writes the changed
 *       state on unmount.</li>
 *   <li>On DISCARD: restore the backup over top of the newly-saved .dat.</li>
 *   <li>On COMMIT: drop the backup.</li>
 * </ol>
 *
 * <p>The backup folder is isolated under the plugin data directory so the
 * mod can be cleanly removed (spec §2.13) without leaving detritus in the
 * world folder.</p>
 *
 * <p>Main-thread use only. There is at most one active backup per UUID at
 * any time.</p>
 */
public final class MountBackup {
    private final Logger log;
    private final MinecraftServer server;
    private final File backupDir;
    /**
     * Lazily resolved on first {@link #datFile} call. At plugin enable,
     * {@code server.overworld()} may be null (worlds are loaded later in
     * startup), so we can't eagerly construct the playerdata path.
     */
    private volatile File playerDataDir;
    /** True once we've logged the "playerdata unavailable" warning once. */
    private volatile boolean warnedUnavailable;
    /** UUIDs whose pre-mount state was "no .dat existed" — restore means delete. */
    private final Set<UUID> wasAbsent = ConcurrentHashMap.newKeySet();

    public MountBackup(final Logger log, final MinecraftServer server, final File pluginDataDir) {
        this.log = log;
        this.server = server;
        this.backupDir = new File(pluginDataDir, "mount-backups");
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            log.warning("Could not create mount backup directory " + backupDir);
        }
        // Deliberately NOT calling server.overworld() here — it may be null
        // at plugin enable. playerDataDir resolves lazily on first use.
    }

    /** Capture the current .dat for a UUID about to be mounted. */
    public void capture(final UUID uuid) {
        final File live = datFile(uuid);
        if (live == null) {
            dev.shadowcore.util.Diag.trace(log, "backup",
                "capture: datFile resolved to null for " + uuid + " (playerdata dir not ready?)");
            return;
        }
        final File bak = backupFile(uuid);
        try {
            if (!live.exists()) {
                wasAbsent.add(uuid);
                dev.shadowcore.util.Diag.trace(log, "backup",
                    "capture: no live .dat for " + uuid + " → marked wasAbsent");
                if (bak.exists() && !bak.delete()) {
                    dev.shadowcore.util.Diag.warn(log, "backup",
                        "Could not clear stale backup " + bak);
                }
                return;
            }
            wasAbsent.remove(uuid);
            Files.copy(live.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
            dev.shadowcore.util.Diag.trace(log, "backup",
                "capture: copied " + live.length() + "B " + live.getName() + " → " + bak.getName());
        } catch (final IOException ex) {
            dev.shadowcore.util.Diag.error(log, "backup",
                "capture failed for " + uuid, ex);
        }
    }

    /** Restore the backup over the live .dat — used on DISCARD unmount. */
    public void restore(final UUID uuid) {
        final File live = datFile(uuid);
        if (live == null) {
            dev.shadowcore.util.Diag.trace(log, "backup",
                "restore: datFile resolved to null for " + uuid);
            return;
        }
        final File bak = backupFile(uuid);
        try {
            if (wasAbsent.remove(uuid)) {
                dev.shadowcore.util.Diag.trace(log, "backup",
                    "restore: uuid was marked wasAbsent → deleting live .dat post-discard");
                if (live.exists() && !live.delete()) {
                    dev.shadowcore.util.Diag.warn(log, "backup",
                        "restore: could not delete live .dat after discard for " + uuid);
                }
                return;
            }
            if (!bak.exists()) {
                dev.shadowcore.util.Diag.warn(log, "backup",
                    "restore: no backup present for " + uuid + " — discard NOT applied");
                return;
            }
            Files.copy(bak.toPath(), live.toPath(), StandardCopyOption.REPLACE_EXISTING);
            dev.shadowcore.util.Diag.info(log, "backup",
                "restore: " + bak.length() + "B " + bak.getName() + " → " + live.getName() + " (DISCARD applied)");
            if (!bak.delete()) {
                dev.shadowcore.util.Diag.trace(log, "backup",
                    "could not delete backup " + bak + " after restore (non-fatal)");
            }
        } catch (final IOException ex) {
            dev.shadowcore.util.Diag.error(log, "backup",
                "restore failed for " + uuid, ex);
        }
    }

    /**
     * Returns true if a pre-mount backup currently exists for the given
     * UUID. Used by reload/discard paths to decide whether a rollback can
     * proceed at all.
     */
    public boolean hasBackup(final UUID uuid) {
        if (uuid == null) return false;
        final File bak = backupFile(uuid);
        return bak != null && bak.exists();
    }

    /** Commit path — drop the backup, live .dat is canonical. */
    public void drop(final UUID uuid) {
        wasAbsent.remove(uuid);
        final File bak = backupFile(uuid);
        if (bak.exists()) {
            if (!bak.delete()) {
                dev.shadowcore.util.Diag.trace(log, "backup",
                    "drop: could not delete backup " + bak + " after commit (non-fatal)");
            } else {
                dev.shadowcore.util.Diag.trace(log, "backup",
                    "drop: removed backup " + bak.getName() + " (commit)");
            }
        } else {
            dev.shadowcore.util.Diag.trace(log, "backup",
                "drop: no backup to remove for " + uuid);
        }
    }

    private File datFile(final UUID uuid) {
        final File dir = resolvePlayerDataDir();
        if (dir == null) return null;
        return new File(dir, uuid + ".dat");
    }

    /**
     * Resolve the world's {@code playerdata} directory, memoized. If the
     * overworld is still not ready (unlikely by the time any mount event
     * fires, but possible in pathological startup ordering), we log once
     * and return null so callers can no-op gracefully.
     */
    private File resolvePlayerDataDir() {
        File cached = playerDataDir;
        if (cached != null) return cached;
        synchronized (this) {
            cached = playerDataDir;
            if (cached != null) return cached;
            try {
                final var overworld = server.overworld();
                if (overworld == null) {
                    if (!warnedUnavailable) {
                        warnedUnavailable = true;
                        log.warning("MountBackup: overworld not ready; DISCARD disposition unavailable until worlds load.");
                    }
                    return null;
                }
                final var world = overworld.getWorld();
                if (world == null) return null;
                cached = new File(world.getWorldFolder(), "playerdata");
                playerDataDir = cached;
                return cached;
            } catch (final RuntimeException ex) {
                log.warning("MountBackup: could not resolve playerdata dir: " + ex.getMessage());
                return null;
            }
        }
    }

    private File backupFile(final UUID uuid) {
        return new File(backupDir, uuid + ".dat.bak");
    }
}
