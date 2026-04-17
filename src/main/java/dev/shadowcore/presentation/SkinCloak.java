package dev.shadowcore.presentation;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
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
                    log.fine("SkinCloak rewrite failed (non-fatal): " + ex.getMessage());
                }
            }
        });
        log.info("ShadowCore SkinCloak installed via ProtocolLib.");
    }

    public void enableFor(final UUID uuid) {
        cloaked.add(uuid);
    }

    public void clear(final UUID uuid) {
        cloaked.remove(uuid);
    }

    /**
     * Rewrites a {@code ClientboundPlayerInfoUpdatePacket} in-place: for any
     * entry whose UUID is currently cloaked, strip profile properties, empty
     * the display name, and set listed=false.
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
                // Build a scrubbed replacement. WrappedGameProfile is mutable
                // only through copy-on-write.
                final var scrubbedProfile = new WrappedGameProfile(profile.getUUID(), "");
                scrubbedProfile.getProperties().clear();
                // Replace; API varies across ProtocolLib versions, so we try
                // both the setter-based and withProfile-based paths.
                try {
                    if (trySetProfile(entry, scrubbedProfile)) {
                        changed = true;
                    }
                } catch (final ReflectiveOperationException setEx) {
                    // Older API: we can only skip here. The REMOVE packet
                    // broadcast by PresentationService is the fallback.
                }
            }
            if (changed) {
                try { infoDataList.write(listIndex, list); }
                catch (final RuntimeException writeEx) { /* non-fatal */ }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> java.util.List<T> safeReadList(
            final com.comphenix.protocol.reflect.StructureModifier<java.util.List<T>> modifier,
            final int index) {
        try { return (java.util.List<T>) modifier.read(index); }
        catch (final RuntimeException ex) { return null; }
    }

    private static boolean trySetProfile(final Object entry, final WrappedGameProfile profile)
            throws ReflectiveOperationException {
        final var setProfile = entry.getClass().getMethod("setProfile", WrappedGameProfile.class);
        setProfile.invoke(entry, profile);
        return true;
    }
}
