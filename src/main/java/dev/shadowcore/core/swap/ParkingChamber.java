package dev.shadowcore.core.swap;

import dev.shadowcore.util.Diag;
import java.io.File;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;

/**
 * Manages the sealed bedrock chambers used to park baseline {@code
 * ServerPlayer}s during shadow sessions.
 *
 * <p>Each parking is in a fresh, never-before-loaded {@code .mca} region
 * file at random remote coordinates. The chamber is a 1×3×1 bedrock box
 * with one source water block inside (which the baseline stands in) and
 * one water block on top of the ceiling (defense in depth). The region
 * file is deleted from disk on unpark, leaving no on-disk evidence.</p>
 *
 * <p>Coordinates are placed near the center of the chosen region, around
 * y=319 (overworld and end) or y=120 (nether — its 256-block-high build
 * limit and bedrock ceiling at y=127 make 120 the practical safe height).
 * Each session gets its own region file, never overlapping with another
 * session's chamber.</p>
 *
 * <p>Region selection picks random {@code (regionX, regionZ)} far from
 * spawn (between 100 000 and 800 000 blocks out, a safe distance inside
 * the vanilla 30 million block world border), and re-rolls if the file
 * already exists.</p>
 */
public final class ParkingChamber {
    private static final int CHAMBER_Y_OVERWORLD = 319;
    private static final int CHAMBER_Y_NETHER = 120;
    private static final int CHAMBER_Y_END = 250;

    /** Min distance in regions (32 chunks each = 512 blocks). */
    private static final int MIN_REGION_DIST = 200;   // ~ 100 000 blocks
    /** Max distance in regions. */
    private static final int MAX_REGION_DIST = 1500;  // ~ 768 000 blocks
    private static final int MAX_REGION_TRIES = 64;

    private final Logger log;
    private final Random random = new Random();

    public ParkingChamber(final Logger log) {
        this.log = log;
    }

    /**
     * The result of building a chamber: where the parked player should be
     * placed, plus the region coordinates needed for cleanup.
     */
    public record Build(Location parkLocation, String worldName,
                        int regionX, int regionZ) {}

    /**
     * Pick a fresh region in the given world, build the chamber, and
     * return placement info. Returns empty if no fresh region could be
     * found or if block placement fails.
     */
    public Optional<Build> buildIn(final World world) {
        if (world == null) return Optional.empty();
        final Optional<int[]> regionOpt = pickFreshRegion(world);
        if (regionOpt.isEmpty()) {
            Diag.warn(log, "park",
                "buildIn: could not find a fresh region in world " + world.getName()
                + " after " + MAX_REGION_TRIES + " tries");
            return Optional.empty();
        }
        final int[] region = regionOpt.get();
        final int regionX = region[0];
        final int regionZ = region[1];
        // Center of region: 32 chunks × 16 blocks = 512 blocks per region.
        // Center block is at regionX*512 + 256.
        final int blockX = regionX * 512 + 256;
        final int blockZ = regionZ * 512 + 256;
        final int y = chamberYFor(world);

        Diag.info(log, "park",
            "buildIn: chosen region (" + regionX + "," + regionZ + ") "
            + "block coords (" + blockX + ", " + y + ", " + blockZ + ") in " + world.getName());

        // Force-load the chunks we need. Chamber occupies a 3×3×3 area
        // centered on (blockX, y+1, blockZ). One chunk covers it.
        final int chunkX = blockX >> 4;
        final int chunkZ = blockZ >> 4;
        final boolean wasLoaded = world.isChunkLoaded(chunkX, chunkZ);
        if (!wasLoaded) {
            world.loadChunk(chunkX, chunkZ, true);
        }
        world.setChunkForceLoaded(chunkX, chunkZ, true);

        try {
            placeChamberAt(world, blockX, y, blockZ);
        } catch (final RuntimeException ex) {
            Diag.error(log, "park", "buildIn: block placement threw: " + ex.getMessage(), ex);
            world.setChunkForceLoaded(chunkX, chunkZ, false);
            return Optional.empty();
        }

        // The park location is the floor block of the chamber (the water
        // tile). Standing in water blocks pet teleport.
        final Location parkLoc = new Location(world,
            blockX + 0.5, y + 1.0, blockZ + 0.5);
        return Optional.of(new Build(parkLoc, world.getName(), regionX, regionZ));
    }

    /**
     * Tear down the chamber: clear the bedrock + water blocks, unforce-load
     * the chunk, and delete the region file from disk.
     */
    public void tearDown(final String worldName, final int regionX, final int regionZ) {
        final World world = Bukkit.getWorld(worldName);
        if (world == null) {
            Diag.warn(log, "park", "tearDown: world '" + worldName + "' is no longer loaded; "
                + "skipping in-world block clear and going straight to region-file delete");
            deleteRegionFile(worldName, regionX, regionZ);
            return;
        }
        final int blockX = regionX * 512 + 256;
        final int blockZ = regionZ * 512 + 256;
        final int y = chamberYFor(world);
        final int chunkX = blockX >> 4;
        final int chunkZ = blockZ >> 4;

        Diag.info(log, "park",
            "tearDown: clearing chamber at (" + blockX + "," + y + "," + blockZ
            + ") in " + worldName + " region (" + regionX + "," + regionZ + ")");

        try {
            clearChamberAt(world, blockX, y, blockZ);
        } catch (final RuntimeException ex) {
            Diag.warn(log, "park", "tearDown: block clear threw: " + ex.getMessage());
        }

        // Unforce-load and unload so the chunk's data is dropped from
        // memory before we delete the file.
        world.setChunkForceLoaded(chunkX, chunkZ, false);
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            world.unloadChunk(chunkX, chunkZ, true);
        }
        deleteRegionFile(worldName, regionX, regionZ);
    }

    // ──────────────────────────────────────────────────────────────────────

    private int chamberYFor(final World world) {
        switch (world.getEnvironment()) {
            case NETHER: return CHAMBER_Y_NETHER;
            case THE_END: return CHAMBER_Y_END;
            case NORMAL:
            default: return CHAMBER_Y_OVERWORLD;
        }
    }

    private Optional<int[]> pickFreshRegion(final World world) {
        final File regionDir = regionDirFor(world);
        if (regionDir == null) {
            Diag.warn(log, "park", "pickFreshRegion: cannot resolve region dir for " + world.getName());
            return Optional.empty();
        }
        for (int i = 0; i < MAX_REGION_TRIES; i++) {
            final int rx = randomRegionCoord();
            final int rz = randomRegionCoord();
            final File mca = new File(regionDir, "r." + rx + "." + rz + ".mca");
            if (!mca.exists()) {
                return Optional.of(new int[] {rx, rz});
            }
        }
        return Optional.empty();
    }

    private int randomRegionCoord() {
        final int magnitude = MIN_REGION_DIST + random.nextInt(MAX_REGION_DIST - MIN_REGION_DIST);
        return random.nextBoolean() ? magnitude : -magnitude;
    }

    /**
     * The Bukkit World API does not expose the on-disk region directory
     * directly. Build the path: {@code <worldFolder>/region/} for the
     * overworld, or {@code <worldFolder>/DIM-1/region/} for the nether,
     * etc. The Paper convention follows vanilla here.
     */
    private File regionDirFor(final World world) {
        final File worldFolder = world.getWorldFolder();
        switch (world.getEnvironment()) {
            case NETHER: return new File(worldFolder, "DIM-1/region");
            case THE_END: return new File(worldFolder, "DIM1/region");
            case NORMAL:
            default: return new File(worldFolder, "region");
        }
    }

    private void deleteRegionFile(final String worldName, final int regionX, final int regionZ) {
        final World world = Bukkit.getWorld(worldName);
        if (world == null) {
            Diag.warn(log, "park", "deleteRegionFile: world '" + worldName
                + "' not loaded; cannot resolve region directory");
            return;
        }
        final File regionDir = regionDirFor(world);
        if (regionDir == null) return;
        final File mca = new File(regionDir, "r." + regionX + "." + regionZ + ".mca");
        if (!mca.exists()) {
            Diag.trace(log, "park", "deleteRegionFile: " + mca.getName() + " already absent");
            return;
        }
        if (mca.delete()) {
            Diag.info(log, "park", "deleteRegionFile: deleted " + mca.getName());
        } else {
            Diag.warn(log, "park",
                "deleteRegionFile: failed to delete " + mca.getAbsolutePath()
                + " — file may still be held by Paper's region-file cache");
        }
        // Also drop the .mca_old (vanilla journal) and entities region file
        // if they exist — they are part of the same logical region.
        final File mcaOld = new File(regionDir, "r." + regionX + "." + regionZ + ".mca_old");
        if (mcaOld.exists()) mcaOld.delete();
        final File entitiesMca = new File(regionDir.getParentFile(),
            "entities/r." + regionX + "." + regionZ + ".mca");
        if (entitiesMca.exists()) entitiesMca.delete();
    }

    /**
     * Place the bedrock-and-water chamber at the given block coordinates.
     * Layout (with Y increasing upward):
     *
     * <pre>
     *   y+3  W           water on ceiling (defense in depth)
     *   y+2  B           bedrock ceiling
     *   y+1  W           water inside (baseline stands here, blocks pets)
     *   y    B           bedrock floor
     *
     *   Walls at y+1 around the water on all 4 horizontal sides.
     * </pre>
     */
    private void placeChamberAt(final World world, final int x, final int y, final int z) {
        // Floor.
        world.getBlockAt(x, y, z).setType(Material.BEDROCK, false);
        // Walls — 4 cardinal sides at y+1, surrounding the water.
        world.getBlockAt(x + 1, y + 1, z).setType(Material.BEDROCK, false);
        world.getBlockAt(x - 1, y + 1, z).setType(Material.BEDROCK, false);
        world.getBlockAt(x, y + 1, z + 1).setType(Material.BEDROCK, false);
        world.getBlockAt(x, y + 1, z - 1).setType(Material.BEDROCK, false);
        // Inside water (the baseline stands in this).
        final Block inside = world.getBlockAt(x, y + 1, z);
        inside.setType(Material.WATER, false);
        if (inside.getBlockData() instanceof final Levelled lev) {
            lev.setLevel(0); // source block
            inside.setBlockData(lev, false);
        }
        // Ceiling.
        world.getBlockAt(x, y + 2, z).setType(Material.BEDROCK, false);
        // Top water (above the bedrock ceiling).
        final Block top = world.getBlockAt(x, y + 3, z);
        top.setType(Material.WATER, false);
        if (top.getBlockData() instanceof final Levelled lev) {
            lev.setLevel(0);
            top.setBlockData(lev, false);
        }
        Diag.trace(log, "park", "placeChamberAt: chamber built at " + x + "," + y + "," + z);
    }

    private void clearChamberAt(final World world, final int x, final int y, final int z) {
        // Restore everything to air. Includes the four walls.
        for (int dy = 0; dy <= 3; dy++) {
            world.getBlockAt(x, y + dy, z).setType(Material.AIR, false);
        }
        world.getBlockAt(x + 1, y + 1, z).setType(Material.AIR, false);
        world.getBlockAt(x - 1, y + 1, z).setType(Material.AIR, false);
        world.getBlockAt(x, y + 1, z + 1).setType(Material.AIR, false);
        world.getBlockAt(x, y + 1, z - 1).setType(Material.AIR, false);
    }
}
