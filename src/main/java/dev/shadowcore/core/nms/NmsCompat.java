package dev.shadowcore.core.nms;

import com.mojang.authlib.GameProfile;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

/**
 * Centralized NMS / authlib compatibility layer.
 *
 * <p>Paper 1.21.11 with paperweight Mojang mappings is the compile target,
 * but several access paths don't work as the compile-time signatures would
 * suggest:</p>
 *
 * <ul>
 *   <li>{@code PlayerList#load(ServerPlayer)} and {@code #save(ServerPlayer)}
 *       are {@code protected} — unreachable from our package.</li>
 *   <li>{@code MinecraftServer#getProfileCache()} has been renamed / moved
 *       across builds; we look it up reflectively and cache the method
 *       handle.</li>
 *   <li>{@code GameProfile} accessors differ between authlib 1.x (methods:
 *       {@code getName}/{@code getId}/{@code getProperties}) and 2.x (where
 *       they've sometimes been renamed to {@code name}/{@code id}). We
 *       probe at class-load time and bind to whichever exists.</li>
 *   <li>{@code ParticleStatus} lives in a different package across 1.21
 *       patch versions. We look up the enum at runtime and fall back to a
 *       safe default if nothing resolves.</li>
 * </ul>
 *
 * <p>Every method here is defensive: if reflection fails it logs and
 * returns a reasonable default, so a single missing field never takes down
 * the plugin.</p>
 */
public final class NmsCompat {
    private NmsCompat() {}

    private static final Logger LOG = Logger.getLogger("ShadowCore-NmsCompat");

    // ─────────────────────────────────────────────────────────────────
    //  GameProfile accessors — bind once at class init.
    // ─────────────────────────────────────────────────────────────────
    private static final MethodHandle PROFILE_NAME;
    private static final MethodHandle PROFILE_ID;
    private static final MethodHandle PROFILE_PROPERTIES;

    static {
        PROFILE_NAME = bindProfileAccessor("getName", "name", String.class);
        PROFILE_ID = bindProfileAccessor("getId", "id", UUID.class);
        PROFILE_PROPERTIES = bindProfileAccessor("getProperties", "properties",
            loadClass("com.mojang.authlib.properties.PropertyMap"));
    }

    private static MethodHandle bindProfileAccessor(
            final String legacy, final String modern, final Class<?> ret) {
        final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        for (final String candidate : new String[]{legacy, modern}) {
            try {
                final MethodHandle mh = lookup.findVirtual(GameProfile.class, candidate, MethodType.methodType(ret));
                dev.shadowcore.util.Diag.info(LOG, "nms",
                    "GameProfile accessor bound: " + candidate + "() → " + ret.getSimpleName());
                return mh;
            } catch (final NoSuchMethodException | IllegalAccessException ignored) {
                // try next candidate
            }
        }
        dev.shadowcore.util.Diag.error(LOG, "nms",
            "GameProfile accessor missing: neither " + legacy + " nor " + modern
            + " found. Plugin will not work correctly.");
        return null;
    }

    public static String profileName(final GameProfile profile) {
        if (profile == null || PROFILE_NAME == null) return null;
        try { return (String) PROFILE_NAME.invoke(profile); }
        catch (final Throwable t) { return null; }
    }

    public static UUID profileId(final GameProfile profile) {
        if (profile == null || PROFILE_ID == null) return null;
        try { return (UUID) PROFILE_ID.invoke(profile); }
        catch (final Throwable t) { return null; }
    }

    public static com.mojang.authlib.properties.PropertyMap profileProperties(final GameProfile profile) {
        if (profile == null || PROFILE_PROPERTIES == null) return null;
        try { return (com.mojang.authlib.properties.PropertyMap) PROFILE_PROPERTIES.invoke(profile); }
        catch (final Throwable t) { return null; }
    }

    // ─────────────────────────────────────────────────────────────────
    //  MinecraftServer#getProfileCache — reflective lookup.
    // ─────────────────────────────────────────────────────────────────
    private static volatile Method cachedGetProfileCacheMethod;

    /**
     * Returns the server-wide GameProfileCache equivalent object, or null.
     * We return Object because the class name has moved between
     * {@code net.minecraft.server.players.GameProfileCache} and other
     * locations across 1.21 revisions.
     */
    public static Object getProfileCache(final MinecraftServer server) {
        if (server == null) return null;
        Method m = cachedGetProfileCacheMethod;
        if (m == null) {
            for (final String name : new String[]{"getProfileCache", "g", "getUserCache", "getPlayerProfileCache"}) {
                try {
                    m = MinecraftServer.class.getMethod(name);
                    cachedGetProfileCacheMethod = m;
                    dev.shadowcore.util.Diag.info(LOG, "nms",
                        "MinecraftServer profile cache accessor bound: " + name + "() → "
                        + m.getReturnType().getSimpleName());
                    break;
                } catch (final NoSuchMethodException ignored) {
                    // try next
                }
            }
            if (m == null) {
                // last-ditch: scan declared methods for a no-arg method returning something named *ProfileCache.
                for (final Method candidate : MinecraftServer.class.getDeclaredMethods()) {
                    if (candidate.getParameterCount() != 0) continue;
                    final String rn = candidate.getReturnType().getSimpleName();
                    if (rn.contains("ProfileCache") || rn.contains("UserCache")) {
                        candidate.setAccessible(true);
                        cachedGetProfileCacheMethod = candidate;
                        m = candidate;
                        dev.shadowcore.util.Diag.info(LOG, "nms",
                            "MinecraftServer profile cache accessor bound by return-type scan: "
                            + candidate.getName() + "() → " + rn);
                        break;
                    }
                }
            }
            if (m == null) {
                dev.shadowcore.util.Diag.error(LOG, "nms",
                    "Could not resolve MinecraftServer#getProfileCache equivalent.");
                return null;
            }
        }
        try {
            final Object result = m.invoke(server);
            if (result == null) {
                dev.shadowcore.util.Diag.warn(LOG, "nms",
                    "profile cache accessor returned null");
            }
            return result;
        } catch (final ReflectiveOperationException ex) {
            dev.shadowcore.util.Diag.error(LOG, "nms",
                "getProfileCache invocation failed", ex);
            return null;
        }
    }

    /**
     * Inserts a GameProfile into the profile cache. The cache class has an
     * {@code add(GameProfile)} method on all known Paper 1.21.x builds.
     */
    public static void addToProfileCache(final Object cache, final GameProfile profile) {
        if (cache == null || profile == null) {
            dev.shadowcore.util.Diag.trace(LOG, "nms",
                "addToProfileCache skipped (null cache=" + (cache == null) + " or null profile=" + (profile == null) + ")");
            return;
        }
        try {
            final Method add = cache.getClass().getMethod("add", GameProfile.class);
            add.invoke(cache, profile);
            dev.shadowcore.util.Diag.trace(LOG, "nms",
                "profile cache add: " + profileName(profile) + " (" + profileId(profile) + ")");
        } catch (final ReflectiveOperationException ex) {
            dev.shadowcore.util.Diag.warn(LOG, "nms",
                "profile cache add failed for " + profileName(profile) + ": " + ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  PlayerList#loadPlayerData / #save — real Paper 1.21.11 API
    //
    //  Paper 1.21.11 has:
    //    public  Optional<CompoundTag> PlayerList#loadPlayerData(NameAndId)
    //    protected void                PlayerList#save(ServerPlayer)
    //
    //  There is NO PlayerList#load(ServerPlayer) — the load sequence is
    //  split: loadPlayerData(NameAndId) returns the raw tag; the caller
    //  applies it to the ServerPlayer via the Entity#readAdditionalSaveData
    //  chain wrapped in a TagValueInput.
    // ─────────────────────────────────────────────────────────────────

    private static volatile Method cachedLoadPlayerDataMethod;
    private static volatile Method cachedSaveMethod;
    private static volatile Method cachedTagValueInputCreate;

    /**
     * Load the on-disk .dat for the given ServerPlayer's identity and apply
     * it to the player. Mirrors the split-phase load vanilla does internally
     * during placeNewPlayer.
     *
     * <p>Sequence:</p>
     * <ol>
     *   <li>Call {@code PlayerList#loadPlayerData(NameAndId)} to read the tag.</li>
     *   <li>If present, wrap it in a {@code TagValueInput} and feed it to
     *       {@code ServerPlayer#readAdditionalSaveData(ValueInput)} (protected;
     *       reflected).</li>
     * </ol>
     *
     * @return true if either (a) a tag was loaded and applied, or (b) no
     *     .dat existed (fresh identity is a valid state, not a failure).
     *     False only if reflection itself failed, which points at a mapping
     *     drift that needs fixing here.
     */
    public static boolean loadPlayerData(final PlayerList list, final ServerPlayer player) {
        if (list == null || player == null) {
            dev.shadowcore.util.Diag.warn(LOG, "nms",
                "loadPlayerData: null argument (list=" + list + " player=" + player + ")");
            return false;
        }
        final UUID uuid = player.getUUID();
        dev.shadowcore.util.Diag.trace(LOG, "nms",
            "loadPlayerData: begin for " + uuid);
        // 1. Call loadPlayerData(NameAndId). We resolve NameAndId from the
        //    player reflectively because player.nameAndId() may be renamed.
        final Object nameAndId = resolveNameAndId(player);
        if (nameAndId == null) {
            dev.shadowcore.util.Diag.warn(LOG, "nms",
                "loadPlayerData: could not resolve NameAndId from ServerPlayer " + uuid);
            return false;
        }
        dev.shadowcore.util.Diag.trace(LOG, "nms",
            "loadPlayerData: NameAndId resolved (" + nameAndId.getClass().getSimpleName() + ")");
        Method m = cachedLoadPlayerDataMethod;
        if (m == null) {
            m = findPublicPlayerListLoadPlayerData();
            if (m == null) {
                dev.shadowcore.util.Diag.error(LOG, "nms",
                    "loadPlayerData: PlayerList#loadPlayerData(NameAndId) not found on this build.");
                return false;
            }
            dev.shadowcore.util.Diag.info(LOG, "nms",
                "loadPlayerData: resolved PlayerList#loadPlayerData(NameAndId)");
            cachedLoadPlayerDataMethod = m;
        }
        final Object result;
        try {
            result = m.invoke(list, nameAndId);
        } catch (final ReflectiveOperationException ex) {
            dev.shadowcore.util.Diag.error(LOG, "nms",
                "loadPlayerData: PlayerList#loadPlayerData invocation failed for " + uuid, ex);
            return false;
        }
        if (!(result instanceof java.util.Optional<?> opt)) {
            dev.shadowcore.util.Diag.warn(LOG, "nms",
                "loadPlayerData: unexpected return type: " + (result == null ? "null" : result.getClass().getName()));
            return false;
        }
        if (opt.isEmpty()) {
            // Fresh identity — no .dat yet. Not an error.
            dev.shadowcore.util.Diag.info(LOG, "nms",
                "loadPlayerData: no .dat on disk for " + uuid + " → treating as fresh identity");
            return true;
        }
        final Object tag = opt.get();
        dev.shadowcore.util.Diag.trace(LOG, "nms",
            "loadPlayerData: .dat loaded for " + uuid + " (" + tag.getClass().getSimpleName() + "), applying to player");
        // 2. Wrap the CompoundTag in a TagValueInput and apply to player.
        final boolean applied = applyTagToPlayer(player, tag);
        dev.shadowcore.util.Diag.info(LOG, "nms",
            "loadPlayerData: apply result=" + applied + " for " + uuid);
        return applied;
    }

    /**
     * Apply a CompoundTag to a ServerPlayer by wrapping it in a TagValueInput
     * and calling the public {@code Entity#load(ValueInput)} entry point.
     *
     * <p>Entity.load is the canonical public load entry (see Entity.java
     * line 2669 in Paper 1.21.11). It reads position, motion, rotation,
     * UUID, and then dispatches through to {@code readAdditionalSaveData}
     * down the class chain (Player, LivingEntity, ServerPlayer each override
     * it). Using Entity.load means we don't have to walk the chain ourselves.</p>
     *
     * <p>TagValueInput.create signature (per Entity.java:3852):
     * {@code create(ProblemReporter, HolderLookup.Provider, CompoundTag)}.
     * The canonical reporter is a {@code ProblemReporter.ScopedCollector}
     * constructed with {@code player.problemPath()} and a logger. We
     * reflectively construct one of those and fall back to a no-op proxy
     * if the type shape differs.</p>
     */
    private static boolean applyTagToPlayer(final ServerPlayer player, final Object tag) {
        final Object valueInput = buildValueInput(player, tag);
        if (valueInput == null) {
            LOG.warning("loadPlayerData: could not construct TagValueInput; .dat not applied.");
            return false;
        }
        // Entity.load(ValueInput) is public — single overload, walks the
        // class chain internally.
        try {
            final Method load = findEntityLoadMethod(player, valueInput.getClass());
            if (load == null) {
                LOG.warning("loadPlayerData: Entity#load(ValueInput) not found.");
                return false;
            }
            load.invoke(player, valueInput);
            return true;
        } catch (final ReflectiveOperationException ex) {
            LOG.log(Level.WARNING, "loadPlayerData: Entity#load invocation failed", ex);
            return false;
        }
    }

    private static volatile Method cachedEntityLoadMethod;

    private static Method findEntityLoadMethod(final ServerPlayer player, final Class<?> valueInputConcreteClass) {
        Method cached = cachedEntityLoadMethod;
        if (cached != null) return cached;
        // Walk up from the player class to find the public load(ValueInput).
        // ValueInput is an interface; the concrete class from TagValueInput.create
        // implements it. We match on "method name is 'load' and first arg type
        // is assignable from the concrete instance class (i.e. ValueInput or
        // a supertype)".
        for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (final Method m : c.getDeclaredMethods()) {
                if (!"load".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isAssignableFrom(valueInputConcreteClass)) continue;
                m.setAccessible(true);
                cachedEntityLoadMethod = m;
                return m;
            }
        }
        return null;
    }

    /**
     * Build a {@code TagValueInput} around {@code tag}, using the player's
     * registryAccess and a ScopedCollector rooted at the player's
     * problemPath. If the ScopedCollector constructor shape doesn't match
     * what we expect, fall back to a no-op ProblemReporter proxy.
     */
    private static Object buildValueInput(final ServerPlayer player, final Object tag) {
        final Method create = findTagValueInputCreate(tag);
        if (create == null) {
            LOG.warning("loadPlayerData: TagValueInput.create(...) not found.");
            return null;
        }
        final Object[] args = buildTagValueInputArgs(create, player, tag);
        if (args == null) return null;
        try {
            return create.invoke(null, args);
        } catch (final ReflectiveOperationException ex) {
            LOG.log(Level.WARNING, "loadPlayerData: TagValueInput.create failed", ex);
            return null;
        }
    }

    private static Method findTagValueInputCreate(final Object tag) {
        Method cached = cachedTagValueInputCreate;
        if (cached != null) return cached;
        try {
            final Class<?> tagValueInput =
                Class.forName("net.minecraft.world.level.storage.TagValueInput");
            for (final Method cm : tagValueInput.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(cm.getModifiers())) continue;
                if (!"create".equals(cm.getName())) continue;
                final Class<?>[] pt = cm.getParameterTypes();
                if (pt.length == 0) continue;
                if (!pt[pt.length - 1].isInstance(tag)) continue;
                cm.setAccessible(true);
                cachedTagValueInputCreate = cm;
                return cm;
            }
        } catch (final ClassNotFoundException ignored) {}
        return null;
    }

    /**
     * Build the argument array for TagValueInput.create(...). Typical 1.21.11
     * signature is {@code create(ProblemReporter, HolderLookup.Provider,
     * CompoundTag)}. We supply sensible defaults for the non-tag parameters
     * by probing their types.
     */
    private static Object[] buildTagValueInputArgs(final Method create, final ServerPlayer player, final Object tag) {
        final Class<?>[] pt = create.getParameterTypes();
        final Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) {
            final Class<?> type = pt[i];
            final String typeName = type.getName();
            if (type.isInstance(tag)) {
                args[i] = tag;
            } else if (typeName.endsWith("ProblemReporter")) {
                // Construct a ProblemReporter.ScopedCollector rooted at the
                // player's problemPath — same pattern Entity.java uses.
                args[i] = resolveProblemReporter(player);
            } else if (typeName.endsWith("HolderLookup$Provider") || typeName.endsWith("Provider")
                || typeName.contains("RegistryAccess")) {
                try {
                    // ServerPlayer#registryAccess() is public on Entity.
                    final Method ra = player.getClass().getMethod("registryAccess");
                    args[i] = ra.invoke(player);
                } catch (final ReflectiveOperationException ex) {
                    LOG.warning("loadPlayerData: could not resolve registryAccess for TagValueInput.");
                    return null;
                }
            } else {
                LOG.warning("loadPlayerData: unexpected TagValueInput.create parameter " + type);
                return null;
            }
        }
        return args;
    }

    private static Object resolveProblemReporter(final ServerPlayer player) {
        // The canonical approach (per Entity.java:3849) is:
        //   new ProblemReporter.ScopedCollector(player.problemPath(), LOGGER)
        // We construct one of those reflectively.
        try {
            final Class<?> pr = Class.forName("net.minecraft.util.ProblemReporter");
            // First try a static field like DISCARDING if it exists (cheapest path).
            for (final String fieldName : new String[]{"DISCARDING", "NOOP", "EMPTY"}) {
                try {
                    final var field = pr.getField(fieldName);
                    return field.get(null);
                } catch (final NoSuchFieldException ignored) {}
            }
            // Construct ProblemReporter.ScopedCollector(ProblemReporter.PathElement, Logger).
            try {
                final Class<?> scopedCollector = Class.forName("net.minecraft.util.ProblemReporter$ScopedCollector");
                final Method problemPath = findMethodByName(player.getClass(), "problemPath", 0);
                final Object pathElement = problemPath == null ? null : problemPath.invoke(player);
                for (final var ctor : scopedCollector.getDeclaredConstructors()) {
                    final Class<?>[] pt = ctor.getParameterTypes();
                    if (pt.length != 2) continue;
                    // Match a PathElement-ish first param and a Logger-ish second.
                    final Object firstArg;
                    if (pathElement != null && pt[0].isInstance(pathElement)) firstArg = pathElement;
                    else continue;
                    final Object secondArg;
                    if (pt[1].getName().equals("org.slf4j.Logger")) {
                        secondArg = org.slf4j.LoggerFactory.getLogger("ShadowCore-LoadPlayerData");
                    } else continue;
                    ctor.setAccessible(true);
                    return ctor.newInstance(firstArg, secondArg);
                }
            } catch (final ClassNotFoundException ignored) {}
            // Last resort: proxy if it's an interface (it isn't on modern Paper,
            // but costs nothing to try).
            if (pr.isInterface()) {
                return java.lang.reflect.Proxy.newProxyInstance(
                    pr.getClassLoader(), new Class<?>[]{pr},
                    (proxy, method, args) -> {
                        final Class<?> rt = method.getReturnType();
                        if (rt == void.class) return null;
                        if (rt == boolean.class) return false;
                        if (rt.isPrimitive()) return 0;
                        return null;
                    }
                );
            }
        } catch (final ReflectiveOperationException ignored) {}
        return null;
    }

    private static Method findMethodByName(final Class<?> startClass, final String name, final int paramCount) {
        for (Class<?> c = startClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (final Method m : c.getDeclaredMethods()) {
                if (name.equals(m.getName()) && m.getParameterCount() == paramCount) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static Object resolveNameAndId(final ServerPlayer player) {
        // ServerPlayer#nameAndId() is used in placeNewPlayer (line 200).
        try {
            final Method m = player.getClass().getMethod("nameAndId");
            return m.invoke(player);
        } catch (final ReflectiveOperationException ex) {
            // Try constructing NameAndId from profile. Fallback only.
            try {
                final Class<?> nameAndIdCls = Class.forName("net.minecraft.server.players.NameAndId");
                final com.mojang.authlib.GameProfile profile = player.getGameProfile();
                final UUID id = profileId(profile);
                final String name = profileName(profile);
                for (final var ctor : nameAndIdCls.getDeclaredConstructors()) {
                    final Class<?>[] pt = ctor.getParameterTypes();
                    if (pt.length != 2) continue;
                    if (pt[0] == UUID.class && pt[1] == String.class) {
                        ctor.setAccessible(true);
                        return ctor.newInstance(id, name);
                    }
                    if (pt[0] == String.class && pt[1] == UUID.class) {
                        ctor.setAccessible(true);
                        return ctor.newInstance(name, id);
                    }
                }
            } catch (final ReflectiveOperationException ex2) {
                LOG.log(Level.FINE, "resolveNameAndId fallback failed", ex2);
            }
            return null;
        }
    }

    private static Method findPublicPlayerListLoadPlayerData() {
        try {
            // loadPlayerData(NameAndId) — NameAndId lives in server.players.
            final Class<?> nameAndIdCls = Class.forName("net.minecraft.server.players.NameAndId");
            return PlayerList.class.getMethod("loadPlayerData", nameAndIdCls);
        } catch (final NoSuchMethodException | ClassNotFoundException ex) {
            return null;
        }
    }

    /**
     * Runs {@link PlayerList}'s protected {@code save(ServerPlayer)} method.
     */
    public static boolean savePlayerData(final PlayerList list, final ServerPlayer player) {
        Method m = cachedSaveMethod;
        if (m == null) {
            m = findPlayerListMethod("save", ServerPlayer.class);
            if (m == null) {
                return fallbackSaveViaPlayerDataStorage(list, player);
            }
            cachedSaveMethod = m;
        }
        try {
            m.invoke(list, player);
            return true;
        } catch (final ReflectiveOperationException ex) {
            LOG.log(Level.FINE, "PlayerList#save invocation failed", ex);
            return fallbackSaveViaPlayerDataStorage(list, player);
        }
    }

    private static Method findPlayerListMethod(final String name, final Class<?>... params) {
        // getDeclaredMethods catches protected methods; getMethod would not.
        for (Class<?> c = PlayerList.class; c != Object.class && c != null; c = c.getSuperclass()) {
            try {
                final Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (final NoSuchMethodException ignored) {}
        }
        return null;
    }

    /**
     * Fallback for save: retrieve the server's PlayerDataStorage and write
     * the player's tag manually. Used when {@link PlayerList#save} cannot be
     * reached reflectively.
     */
    private static boolean fallbackSaveViaPlayerDataStorage(final PlayerList list, final ServerPlayer player) {
        final Object storage = getPlayerDataStorage(list);
        if (storage == null) return false;
        try {
            for (final Method m : storage.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isInstance(player)) continue;
                if (!m.getName().toLowerCase().contains("save") && !m.getName().equals("save")) continue;
                m.setAccessible(true);
                m.invoke(storage, player);
                return true;
            }
        } catch (final ReflectiveOperationException ex) {
            LOG.log(Level.FINE, "PlayerDataStorage save fallback failed", ex);
        }
        return false;
    }

    private static Object getPlayerDataStorage(final PlayerList list) {
        if (list == null) return null;
        // Try known method names.
        for (final String n : new String[]{"playerIo", "getPlayerIo", "getPlayerDataStorage"}) {
            try {
                final Method m = PlayerList.class.getDeclaredMethod(n);
                m.setAccessible(true);
                return m.invoke(list);
            } catch (final ReflectiveOperationException ignored) {}
        }
        // Try known field names.
        for (final String fn : new String[]{"playerIo", "playerDataStorage"}) {
            try {
                final Field f = PlayerList.class.getDeclaredField(fn);
                f.setAccessible(true);
                return f.get(list);
            } catch (final ReflectiveOperationException ignored) {}
        }
        // Scan for a field whose class simple-name contains PlayerDataStorage.
        for (final Field f : PlayerList.class.getDeclaredFields()) {
            if (f.getType().getSimpleName().contains("PlayerDataStorage")
                || f.getType().getSimpleName().contains("PlayerIo")) {
                try {
                    f.setAccessible(true);
                    return f.get(list);
                } catch (final IllegalAccessException ignored) {}
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────
    //  ParticleStatus enum lookup — it's moved across builds.
    // ─────────────────────────────────────────────────────────────────
    private static volatile Object cachedParticleStatusAll;

    /**
     * Returns the {@code ParticleStatus.ALL} enum constant, wherever it
     * lives in this build. Used in {@link net.minecraft.server.level.ClientInformation}
     * construction. Returns null if no equivalent enum exists.
     */
    public static Object particleStatusAll() {
        Object cached = cachedParticleStatusAll;
        if (cached != null) return cached;
        for (final String className : new String[]{
                "net.minecraft.server.level.ParticleStatus",
                "net.minecraft.client.ParticleStatus",
                "net.minecraft.core.particles.ParticleStatus"}) {
            try {
                final Class<?> c = Class.forName(className);
                for (final Object o : c.getEnumConstants()) {
                    if ("ALL".equals(((Enum<?>) o).name())) {
                        cachedParticleStatusAll = o;
                        return o;
                    }
                }
            } catch (final ClassNotFoundException ignored) {}
        }
        return null;
    }

    private static Class<?> loadClass(final String name) {
        try { return Class.forName(name); }
        catch (final ClassNotFoundException ex) { return null; }
    }
}
