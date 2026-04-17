package dev.shadowcore.core.nms;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

/**
 * Reflective access to {@link PlayerList} internal state.
 *
 * <p>Paper/Mojang 1.21.11 does not expose a public method to insert a
 * {@link ServerPlayer} into the players list outside of the
 * {@link PlayerList#placeNewPlayer} path, which we cannot use because it
 * assumes a fresh connection. We therefore directly mutate the internal
 * collections that vanilla code reads: {@code players} (List), {@code
 * playersByUUID} (Map), and {@code playersByName} (Map).</p>
 *
 * <h2>Why this is safe-ish</h2>
 * These collections are read-mostly after initial placement. Our insertion
 * happens on the main thread, synchronous with other placements, and the
 * reader loops in vanilla are tolerant of concurrent additions because they
 * copy-iterate. The one operation that is NOT safe is iteration during
 * mutation on the main thread — but the main thread is single-threaded for
 * exactly this reason, so we're fine.
 *
 * <h2>Why this is fragile</h2>
 * Field names here are from Mojang mappings (paperweight-userdev). If Mojang
 * renames any of these between minor versions, this class breaks at runtime
 * and needs updating. We cache reflection results on first access; if any
 * field is missing, we log and return false.
 */
public final class PlayerListAccess {
    private PlayerListAccess() {}

    private static final Logger LOG = Logger.getLogger("ShadowCore-PlayerListAccess");

    private static volatile Field PLAYERS_FIELD;
    private static volatile Field BY_UUID_FIELD;
    private static volatile Field BY_NAME_FIELD;
    private static volatile boolean reflectionInitialized;

    /**
     * Insert {@code mounted} into the {@link PlayerList}'s internal lookup
     * collections so that every vanilla and third-party code path that goes
     * through {@link PlayerList#getPlayer(UUID)} or {@link
     * PlayerList#getPlayerByName(String)} finds the mounted identity.
     *
     * @return true if insertion succeeded on every collection.
     */
    @SuppressWarnings("unchecked")
    public static boolean registerMounted(final PlayerList list, final ServerPlayer mounted) {
        if (!initReflection(list.getClass())) return false;
        try {
            final Object playersObj = PLAYERS_FIELD.get(list);
            final Object byUuidObj = BY_UUID_FIELD.get(list);
            final Object byNameObj = BY_NAME_FIELD.get(list);
            if (playersObj instanceof List<?>) {
                final List<ServerPlayer> players = (List<ServerPlayer>) playersObj;
                if (!players.contains(mounted)) players.add(mounted);
            }
            if (byUuidObj instanceof Map<?, ?>) {
                final Map<UUID, ServerPlayer> byUuid = (Map<UUID, ServerPlayer>) byUuidObj;
                byUuid.put(mounted.getUUID(), mounted);
            }
            if (byNameObj instanceof Map<?, ?>) {
                final Map<String, ServerPlayer> byName = (Map<String, ServerPlayer>) byNameObj;
                byName.put(mounted.getGameProfile().getName(), mounted);
            }
            return true;
        } catch (final ReflectiveOperationException ex) {
            LOG.severe("PlayerListAccess.registerMounted failed: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Remove {@code mounted} from the internal collections. Call this only
     * if {@link PlayerList#remove} did not do so — normally remove DOES clean
     * everything up, but if a mount failed partway through we may need to
     * roll back manually.
     */
    @SuppressWarnings("unchecked")
    public static boolean unregisterMounted(final PlayerList list, final ServerPlayer mounted) {
        if (!initReflection(list.getClass())) return false;
        try {
            final Object playersObj = PLAYERS_FIELD.get(list);
            final Object byUuidObj = BY_UUID_FIELD.get(list);
            final Object byNameObj = BY_NAME_FIELD.get(list);
            if (playersObj instanceof List<?>) ((List<ServerPlayer>) playersObj).remove(mounted);
            if (byUuidObj instanceof Map<?, ?>) ((Map<UUID, ServerPlayer>) byUuidObj).remove(mounted.getUUID());
            if (byNameObj instanceof Map<?, ?>) ((Map<String, ServerPlayer>) byNameObj).remove(mounted.getGameProfile().getName());
            return true;
        } catch (final ReflectiveOperationException ex) {
            LOG.severe("PlayerListAccess.unregisterMounted failed: " + ex.getMessage());
            return false;
        }
    }

    private static synchronized boolean initReflection(final Class<?> playerListClass) {
        if (reflectionInitialized) return PLAYERS_FIELD != null && BY_UUID_FIELD != null && BY_NAME_FIELD != null;
        try {
            PLAYERS_FIELD = findField(playerListClass, "players", List.class);
            BY_UUID_FIELD = findField(playerListClass, "playersByUUID", Map.class);
            BY_NAME_FIELD = findField(playerListClass, "playersByName", Map.class);
            reflectionInitialized = true;
            return true;
        } catch (final NoSuchFieldException ex) {
            LOG.severe("PlayerListAccess: missing expected PlayerList field — " + ex.getMessage());
            reflectionInitialized = true;
            return false;
        }
    }

    private static Field findField(Class<?> c, final String nameCandidate, final Class<?> ofType) throws NoSuchFieldException {
        while (c != null) {
            for (final Field f : c.getDeclaredFields()) {
                if (!ofType.isAssignableFrom(f.getType())) continue;
                if (f.getName().equals(nameCandidate)) {
                    f.setAccessible(true);
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        // Second pass: best-effort match on assignable type + any name containing the candidate.
        throw new NoSuchFieldException(nameCandidate + " (" + ofType.getSimpleName() + ")");
    }
}
