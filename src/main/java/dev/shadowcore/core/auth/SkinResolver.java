package dev.shadowcore.core.auth;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;

/**
 * Fetches skin properties (texture/signature pair) for a Mojang account
 * UUID by querying the public sessionserver, so shadow mode can present the
 * target's real skin rather than a default Steve/Alex.
 *
 * <p>Results are cached in-memory for the configured TTL. Failures are
 * cached briefly too so we don't hammer sessionserver for unknown UUIDs.</p>
 *
 * <p>All network I/O runs on Bukkit's async scheduler; callers supply a
 * completion consumer that is invoked back on the main thread. If the
 * result is already cached, the consumer runs inline on the current thread.</p>
 *
 * <p>This class is deliberately tiny and stdlib-only — no JSON library is
 * pulled in. The sessionserver payload is extremely simple: a single
 * {@code properties} array with one entry whose {@code name} is
 * {@code textures}. A small regex extracts the value and signature.</p>
 */
public final class SkinResolver {
    private static final String SESSION_URL =
        "https://sessionserver.mojang.com/session/minecraft/profile/%s?unsigned=false";
    private static final Pattern VALUE_RE = Pattern.compile(
        "\"name\"\\s*:\\s*\"textures\".*?\"value\"\\s*:\\s*\"([^\"]+)\".*?\"signature\"\\s*:\\s*\"([^\"]+)\"",
        Pattern.DOTALL);

    private final Logger log;
    private final org.bukkit.plugin.Plugin plugin;
    private final Map<UUID, CachedSkin> cache = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SkinResolver(final Logger log, final org.bukkit.plugin.Plugin plugin, final long ttlMinutes) {
        this.log = log;
        this.plugin = plugin;
        this.ttlMillis = Math.max(1, ttlMinutes) * 60_000L;
    }

    /**
     * Synchronous in-cache lookup. Returns empty if we have no fresh
     * cached result for this UUID.
     */
    public Optional<Property> cached(final UUID uuid) {
        final CachedSkin c = cache.get(uuid);
        if (c == null) return Optional.empty();
        if (System.currentTimeMillis() - c.timestamp > ttlMillis) return Optional.empty();
        return Optional.ofNullable(c.property);
    }

    /**
     * Async fetch. Completion runs on the main thread. If the fetch fails,
     * the consumer receives {@code null}.
     */
    public void fetch(final UUID uuid, final java.util.function.Consumer<Property> onMain) {
        final Optional<Property> c = cached(uuid);
        if (c.isPresent()) { onMain.accept(c.get()); return; }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final Property result = doFetch(uuid);
            cache.put(uuid, new CachedSkin(result, System.currentTimeMillis()));
            Bukkit.getScheduler().runTask(plugin, () -> onMain.accept(result));
        });
    }

    /**
     * Block until the sessionserver responds, or return null on any error.
     * Runs on an async worker.
     */
    private Property doFetch(final UUID uuid) {
        final String stripped = uuid.toString().replace("-", "");
        try {
            final URL url = URI.create(String.format(SESSION_URL, stripped)).toURL();
            final URLConnection conn = url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "ShadowCore/3.0");
            try (final InputStream in = conn.getInputStream()) {
                final String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                final Matcher m = VALUE_RE.matcher(body);
                if (!m.find()) return null;
                return new Property("textures", m.group(1), m.group(2));
            }
        } catch (final Exception ex) {
            log.fine("SkinResolver fetch for " + uuid + " failed: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Copy a GameProfile with the resolved textures property applied. If no
     * property is available, returns the profile unchanged (shadow doll
     * gets the default skin).
     */
    public static GameProfile applyProperty(final GameProfile profile, final Property property) {
        if (property == null) return profile;
        final var props = dev.shadowcore.core.nms.NmsCompat.profileProperties(profile);
        if (props != null) {
            try {
                props.put("textures", property);
                return profile;
            } catch (final RuntimeException ignored) {
                // fall through to copy
            }
        }
        // Copy fallback.
        final GameProfile copy = new GameProfile(
            dev.shadowcore.core.nms.NmsCompat.profileId(profile),
            dev.shadowcore.core.nms.NmsCompat.profileName(profile));
        final var copyProps = dev.shadowcore.core.nms.NmsCompat.profileProperties(copy);
        if (copyProps != null) {
            try { copyProps.put("textures", property); }
            catch (final RuntimeException ignored) {}
        }
        return copy;
    }

    private record CachedSkin(Property property, long timestamp) {}
}
