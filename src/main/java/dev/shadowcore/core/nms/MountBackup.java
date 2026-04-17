package dev.shadowcore.core.nms;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private final File playerDataDir;
    private final File backupDir;
    /** UUIDs whose pre-mount state was "no .dat existed" — restore means delete. */
    private final Set<UUID> wasAbsent = ConcurrentHashMap.newKeySet();

    public MountBackup(final Logger log, final MinecraftServer server, final File pluginDataDir) {
        this.log = log;
        this.server = server;
        this.playerDataDir = new File(server.overworld().getWorld().getWorldFolder(), "playerdata");
        this.backupDir = new File(pluginDataDir, "mount-backups");
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            log.warning("Could not create mount backup directory " + backupDir);
        }
    }

    /** Capture the current .dat for a UUID about to be mounted. */
    public void capture(final UUID uuid) {
        final File live = datFile(uuid);
        final File bak = backupFile(uuid);
        try {
            if (!live.exists()) {
                wasAbsent.add(uuid);
                if (bak.exists() && !bak.delete()) {
                    log.warning("Could not clear stale backup " + bak);
                }
                return;
            }
            wasAbsent.remove(uuid);
            Files.copy(live.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ex) {
            log.warning("MountBackup.capture failed for " + uuid + ": " + ex.getMessage());
        }
    }

    /** Restore the backup over the live .dat — used on DISCARD unmount. */
    public void restore(final UUID uuid) {
        final File live = datFile(uuid);
        final File bak = backupFile(uuid);
        try {
            if (wasAbsent.remove(uuid)) {
                if (live.exists() && !live.delete()) {
                    log.warning("MountBackup.restore: could not delete live .dat after discard for " + uuid);
                }
                return;
            }
            if (!bak.exists()) {
                log.warning("MountBackup.restore: no backup present for " + uuid + " — discard not applied");
                return;
            }
            Files.copy(bak.toPath(), live.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (!bak.delete()) {
                log.fine("Could not delete backup " + bak + " after restore (non-fatal)");
            }
        } catch (final IOException ex) {
            log.warning("MountBackup.restore failed for " + uuid + ": " + ex.getMessage());
        }
    }

    /** Commit path — drop the backup, live .dat is canonical. */
    public void drop(final UUID uuid) {
        wasAbsent.remove(uuid);
        final File bak = backupFile(uuid);
        if (bak.exists() && !bak.delete()) {
            log.fine("Could not delete backup " + bak + " after commit (non-fatal)");
        }
    }

    private File datFile(final UUID uuid) {
        return new File(playerDataDir, uuid + ".dat");
    }

    private File backupFile(final UUID uuid) {
        return new File(backupDir, uuid + ".dat.bak");
    }
}
