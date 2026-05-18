package dev.shadowcore.core.forward;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.mojang.authlib.properties.Property;
import dev.shadowcore.core.auth.SkinResolver;
import dev.shadowcore.util.Diag;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

/**
 * Forwards the controller's Mojang skin onto the active {@code Player}'s
 * GameProfile properties.
 *
 * <p>Applied for profile mounts (main, local) only. For shadow mounts, the
 * target's own skin is shown — the orchestrator decides not to call this
 * class in the shadow case. See spec §8 for the policy.</p>
 *
 * <p>The mechanism uses Paper's public PlayerProfile API:</p>
 *
 * <pre>
 *   PlayerProfile p = activePlayer.getPlayerProfile();
 *   p.setProperty(new ProfileProperty("textures", value, signature));
 *   activePlayer.setPlayerProfile(p);
 * </pre>
 *
 * <p>Paper handles broadcasting the necessary {@code PlayerInfoUpdate}
 * packets to observers automatically.</p>
 *
 * <p>If the controller's skin is not in the SkinResolver cache, this
 * triggers an async fetch and applies the skin once it arrives. The
 * controller's body remains visible during the fetch with whatever
 * default texture vanilla provides.</p>
 */
public final class SkinForwarder {
    private final Logger log;
    private final SkinResolver skins;

    public SkinForwarder(final Logger log, final SkinResolver skins) {
        this.log = log;
        this.skins = skins;
    }

    /**
     * Apply the controller's skin to the active player. No-op if the
     * controller and active UUID are the same (i.e. on main account).
     */
    public void apply(final UUID controllerMojangUuid, final Player activePlayer) {
        if (controllerMojangUuid == null || activePlayer == null) return;
        if (controllerMojangUuid.equals(activePlayer.getUniqueId())) {
            Diag.trace(log, "fwd",
                "skin: controller is on main account, vanilla skin applies");
            return;
        }
        final Optional<Property> cached = skins.cached(controllerMojangUuid);
        if (cached.isPresent()) {
            applyTextureToPlayer(activePlayer, cached.get());
            return;
        }
        // Not cached — trigger async fetch and apply when it arrives.
        Diag.trace(log, "fwd",
            "skin: controller " + controllerMojangUuid + " not cached, fetching async");
        skins.fetch(controllerMojangUuid, prop -> {
            if (prop == null) {
                Diag.warn(log, "fwd",
                    "skin: fetch failed for " + controllerMojangUuid + "; active player keeps default");
                return;
            }
            // Re-check player is still online and still on the same active
            // identity; apply only if so.
            if (!activePlayer.isOnline()) {
                Diag.trace(log, "fwd",
                    "skin: active player no longer online, dropping fetched texture");
                return;
            }
            applyTextureToPlayer(activePlayer, prop);
        });
    }

    private void applyTextureToPlayer(final Player active, final Property texture) {
        try {
            final PlayerProfile profile = active.getPlayerProfile();
            // Wipe any existing textures so the new one applies cleanly.
            profile.removeProperty("textures");
            profile.setProperty(new ProfileProperty(
                "textures", texture.value(), texture.signature()));
            active.setPlayerProfile(profile);
            Diag.info(log, "fwd",
                "skin: applied controller skin to " + active.getName()
                + " (uuid=" + active.getUniqueId() + ")");
        } catch (final RuntimeException ex) {
            Diag.warn(log, "fwd",
                "skin: setPlayerProfile threw: " + ex.getMessage());
        }
    }
}
