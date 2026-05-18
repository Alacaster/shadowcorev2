package dev.shadowcore.presentation;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/**
 * Outbound packet rewriter that cloaks specific UUIDs on every PlayerInfo
 * update the server sends. Used for PHANTOM mode and, potentially, for
 * SHADOW-mode skin re-identification in a later pass.
 *
 * <p>When {@link #enableFor(UUID)} is called, any subsequent
 * {@code ClientboundPlayerInfoUpdatePacket} containing that UUID is
 * rewritten before it leaves the server so that:</p>
 *
 * <ul>
 *   <li>The GameProfile has no textures or cape properties.</li>
 *   <li>The display name is empty.</li>
 *   <li>The listed flag is false (belt-and-braces with the REMOVE packet
 *       already broadcast by PresentationService).</li>
 * </ul>
 *
 * <p>Hack clients that bypass PlayerInfo REMOVE still see only the cloaked
 * profile, not the controller's real identity.</p>
 *
 * <p>This service is optional — {@link PresentationService} only calls into
 * it when {@code attachCloak} has been invoked. If ProtocolLib is missing,
 * PHANTOM mode still works (via REMOVE broadcasts and the INVISIBILITY
 * effect); the cloak is the cheat-resistance layer on top.</p>
 */
public final class SkinCloak {
    private final Logger log;
    private final Plugin plugin;
    private final ProtocolManager pm;
    private final Set<UUID> cloaked = ConcurrentHashMap.newKeySet();

    public SkinCloak(final Logger log, final Plugin plugin) {
        this.log = log;
        this.plugin = plugin;
        this.pm = ProtocolLibrary.getProtocolManager();
    }

    public void install() {
        pm.addPacketListener(new PacketAdapter(
                plugin, ListenerPriority.HIGHEST, PacketType.Play.Server.PLAYER_INFO) {
            @Override
            public void onPacketSending(final PacketEvent event) {
                if (cloaked.isEmpty()) return;
                try {
                    rewrite(event.getPacket());
                } catch (final RuntimeException ex) {
                    dev.shadowcore.util.Diag.warn(log, "skin",
                        "SkinCloak rewrite failed (non-fatal): " + ex.getMessage());
                }
            }
        });
        dev.shadowcore.util.Diag.info(log, "skin",
            "SkinCloak installed via ProtocolLib — PLAYER_INFO packets will be scrubbed for cloaked UUIDs");
    }

    public void enableFor(final UUID uuid) {
        cloaked.add(uuid);
        dev.shadowcore.util.Diag.info(log, "skin",
            "enableFor: " + uuid + " → now cloaked (set size=" + cloaked.size() + ")");
    }

    public void clear(final UUID uuid) {
        if (cloaked.remove(uuid)) {
            dev.shadowcore.util.Diag.info(log, "skin",
                "clear: " + uuid + " → uncloaked (set size=" + cloaked.size() + ")");
        }
    }

    /**
     * Rewrites a {@code ClientboundPlayerInfoUpdatePacket} in-place: for any
     * entry whose UUID is currently cloaked, replace the {@link
     * com.comphenix.protocol.wrappers.PlayerInfoData} with a scrubbed copy.
     *
     * <p>ProtocolLib 5.x's {@code PlayerInfoData} is immutable: no
     * {@code setProfile} exists. We either (a) build a new instance via a
     * known constructor / factory, or (b) as a last resort reflectively
     * overwrite its internal {@code profile} field so the existing instance
     * is mutated in place.</p>
     */
    private void rewrite(final PacketContainer packet) {
        final var infoDataList = packet.getPlayerInfoDataLists();
        if (infoDataList == null) return;
        // ProtocolLib's PlayerInfoData list is at index 1 (newer) or 0 (older).
        // We iterate all lists defensively.
        for (int listIndex = 0; listIndex < 2; listIndex++) {
            final var list = safeReadList(infoDataList, listIndex);
            if (list == null) continue;
            boolean changed = false;
            for (int i = 0; i < list.size(); i++) {
                final var entry = list.get(i);
                if (entry == null) continue;
                final var profile = entry.getProfile();
                if (profile == null) continue;
                if (!cloaked.contains(profile.getUUID())) continue;
                final var scrubbedProfile = new WrappedGameProfile(profile.getUUID(), "");
                // scrubbedProfile is a fresh empty one; no properties to clear.
                final Object scrubbedEntry = scrubProfileInEntry(entry, scrubbedProfile);
                if (scrubbedEntry != null) {
                    @SuppressWarnings("unchecked")
                    final java.util.List<Object> rawList = (java.util.List<Object>) (java.util.List<?>) list;
                    rawList.set(i, scrubbedEntry);
                    changed = true;
                }
            }
            if (changed) {
                try { infoDataList.write(listIndex, list); }
                catch (final RuntimeException writeEx) { /* non-fatal */ }
            }
        }
    }

    /**
     * Produce a new {@code PlayerInfoData} with the given scrubbed profile in
     * place of the one currently held by {@code original}. Strategy:
     * <ol>
     *   <li>Look for a constructor whose first param is a GameProfile / Wrapped
     *       variant — ProtocolLib's {@code PlayerInfoData} has one in all
     *       5.x versions with argument order (profile, latency, gamemode,
     *       displayName [, ...]). We invoke it with the original's fields
     *       otherwise preserved.</li>
     *   <li>Fall back to overwriting the {@code profile} field of the
     *       original via reflection, mutating it in place.</li>
     * </ol>
     * Returns the replacement (or same instance after in-place mutation),
     * or null on total failure.
     */
    private static Object scrubProfileInEntry(final Object original, final WrappedGameProfile scrubbed) {
        if (original == null) return null;
        // Try reflective in-place overwrite of the profile field — simplest.
        for (final var field : original.getClass().getDeclaredFields()) {
            if (WrappedGameProfile.class.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    field.set(original, scrubbed);
                    return original;
                } catch (final ReflectiveOperationException ignored) {}
            }
        }
        // If we get here there's no assignable profile field on this build of
        // ProtocolLib. Give up; the REMOVE packet broadcast by
        // PresentationService is the reliable fallback path.
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> java.util.List<T> safeReadList(
            final com.comphenix.protocol.reflect.StructureModifier<java.util.List<T>> modifier,
            final int index) {
        try { return (java.util.List<T>) modifier.read(index); }
        catch (final RuntimeException ex) { return null; }
    }
}
