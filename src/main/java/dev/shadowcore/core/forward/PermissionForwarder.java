package dev.shadowcore.core.forward;

import dev.shadowcore.util.Diag;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Mirrors the controller's permission group memberships onto the active
 * {@code ServerPlayer}'s UUID, when a supported permission plugin is
 * present.
 *
 * <p>Permission plugins like LuckPerms key on UUID. When a controller is
 * on a profile or shadowing, the active UUID is different from their
 * Mojang UUID, so the permission plugin sees an unknown user with no
 * groups. This forwarder copies the controller's group memberships onto
 * the active UUID at mount time so commands and chat formatting work as
 * expected.</p>
 *
 * <p>Currently supported: LuckPerms (via reflection so ShadowCore does not
 * hard-depend on it). When LuckPerms is not present, this class is a
 * no-op; callers can call {@link #apply} freely without checking.</p>
 *
 * <p>Reflective rather than typed because adding a {@code compileOnly}
 * dependency on LuckPerms's API jar is fine in principle but would create
 * a hard ClassNotFoundException at any LuckPerms-related call site if
 * LuckPerms is removed from the server. The reflective approach keeps the
 * plugin running cleanly even with no permission plugin installed.</p>
 */
public final class PermissionForwarder {
    private final Logger log;
    private boolean luckPermsAvailable;
    private Object luckPermsApi; // net.luckperms.api.LuckPerms
    private Class<?> userManagerClass;
    private Class<?> userClass;

    public PermissionForwarder(final Logger log) {
        this.log = log;
    }

    /** Called at plugin enable, after Bukkit's plugin manager has loaded. */
    public void detect() {
        final Plugin lp = Bukkit.getPluginManager().getPlugin("LuckPerms");
        if (lp == null || !lp.isEnabled()) {
            Diag.info(log, "fwd",
                "perm: LuckPerms not detected; permission forwarding will be a no-op");
            this.luckPermsAvailable = false;
            return;
        }
        try {
            final Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            this.luckPermsApi = providerClass.getMethod("get").invoke(null);
            this.userManagerClass = Class.forName("net.luckperms.api.model.user.UserManager");
            this.userClass = Class.forName("net.luckperms.api.model.user.User");
            this.luckPermsAvailable = true;
            Diag.info(log, "fwd", "perm: LuckPerms detected via reflection — forwarding enabled");
        } catch (final ReflectiveOperationException ex) {
            Diag.warn(log, "fwd",
                "perm: LuckPerms is loaded but its API could not be reflected: "
                + ex.getMessage() + "; permission forwarding disabled");
            this.luckPermsAvailable = false;
        }
    }

    /**
     * Copy the controller's LuckPerms data onto the active player's UUID.
     *
     * <p>This is a best-effort operation. The mechanism: load the
     * controller's User from LuckPerms, snapshot their primary group and
     * permission set, then load the active player's User and apply the
     * snapshot. LuckPerms's auto-save on user-modify takes care of
     * persisting it for the duration of the active session.</p>
     */
    public void apply(final UUID controllerMojangUuid, final Player activePlayer) {
        if (!luckPermsAvailable) return;
        if (controllerMojangUuid == null || activePlayer == null) return;
        if (controllerMojangUuid.equals(activePlayer.getUniqueId())) {
            Diag.trace(log, "fwd",
                "perm: controller UUID == active UUID, skipping forward");
            return;
        }
        try {
            // Load both users via UserManager.loadUser(UUID) — returns
            // CompletableFuture<User>. We do .join() because we're already
            // post-mount on the main thread and the controller's data is
            // typically cached.
            final Object userManager = luckPermsApi.getClass().getMethod("getUserManager").invoke(luckPermsApi);
            final Object controllerFuture = userManagerClass.getMethod("loadUser", UUID.class)
                .invoke(userManager, controllerMojangUuid);
            final Object controllerUser = controllerFuture.getClass().getMethod("join").invoke(controllerFuture);
            final Object activeFuture = userManagerClass.getMethod("loadUser", UUID.class)
                .invoke(userManager, activePlayer.getUniqueId());
            final Object activeUser = activeFuture.getClass().getMethod("join").invoke(activeFuture);

            // Copy primary group.
            final String primaryGroup = (String) userClass.getMethod("getPrimaryGroup").invoke(controllerUser);
            userClass.getMethod("setPrimaryGroup", String.class).invoke(activeUser, primaryGroup);

            // Copy explicit nodes via data().nodes().
            final Object controllerData = userClass.getMethod("data").invoke(controllerUser);
            final Object activeData = userClass.getMethod("data").invoke(activeUser);
            final Class<?> nodeMapClass = controllerData.getClass();
            final Object nodes = nodeMapClass.getMethod("toCollection").invoke(controllerData);
            // Clear existing data on active first to prevent stale stacking
            nodeMapClass.getMethod("clear").invoke(activeData);
            // Add controller's nodes
            for (final Object node : (Iterable<?>) nodes) {
                nodeMapClass.getMethod("add", Class.forName("net.luckperms.api.node.Node"))
                    .invoke(activeData, node);
            }

            // Save the active user back so LuckPerms persists the change.
            final Object saveFuture = userManagerClass.getMethod("saveUser", userClass)
                .invoke(userManager, activeUser);
            saveFuture.getClass().getMethod("join").invoke(saveFuture);

            Diag.info(log, "fwd",
                "perm: forwarded LuckPerms data from " + controllerMojangUuid
                + " to " + activePlayer.getUniqueId() + " (group=" + primaryGroup + ")");
        } catch (final ReflectiveOperationException ex) {
            Diag.warn(log, "fwd",
                "perm: forward failed via reflection: " + ex.getMessage()
                + " (controller=" + controllerMojangUuid + " active=" + activePlayer.getUniqueId() + ")");
        } catch (final RuntimeException ex) {
            Diag.warn(log, "fwd",
                "perm: forward threw: " + ex.getMessage());
        }
    }

    public boolean luckPermsAvailable() { return luckPermsAvailable; }
}
