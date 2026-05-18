package dev.shadowcore.core.swap;

import dev.shadowcore.model.MountDisposition;
import dev.shadowcore.model.MountedIdentity;
import dev.shadowcore.util.Diag;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Logging-only {@link IdentitySwap} used during the skeleton phase
 * (step 1 of the roadmap).
 *
 * <p>Every method logs what would happen, pushes an in-game actionbar
 * notification to the controller so the effect is visible without
 * reading the console, and returns success without mutating any
 * ServerPlayer state. The goal is to prove the manager + engine +
 * listener pipeline dispatches cleanly in the absence of real swap
 * machinery.</p>
 *
 * <p>This class will be removed once the real {@code TransferIdentitySwap}
 * ships in step 2. In the meantime, mounting will not actually change the
 * player's identity — the feedback messages confirm dispatch happened,
 * but the inventory, position, UUID, and name remain those of whoever
 * originally joined.</p>
 */
public final class StubIdentitySwap implements IdentitySwap {
    private final Logger log;

    public StubIdentitySwap(final Logger log) {
        this.log = log;
    }

    @Override
    public boolean mount(final Session session, final MountedIdentity identity) {
        Diag.info(log, "swap", "STUB mount: " + ctrl(session) + " -> " + id(identity));
        notify(session, "stub: mount " + id(identity));
        session.setCurrent(session.current(), identity, identity.baseline());
        return true;
    }

    @Override
    public boolean swap(final Session session, final MountedIdentity next,
                        final MountDisposition dispositionForOld) {
        Diag.info(log, "swap", "STUB swap: " + ctrl(session)
            + " -> " + id(next) + " (old disposition=" + dispositionForOld + ")");
        notify(session, "stub: swap to " + id(next) + " (disposition " + dispositionForOld + ")");
        session.setCurrent(session.current(), next, next.baseline());
        return true;
    }

    @Override
    public void persist(final Session session) {
        Diag.trace(log, "swap", "STUB persist: " + ctrl(session));
        // no user-facing notification for persist — it happens routinely
    }

    @Override
    public void unmount(final Session session, final MountDisposition disposition) {
        Diag.info(log, "swap", "STUB unmount: " + ctrl(session) + " (disposition=" + disposition + ")");
        notify(session, "stub: unmount (disposition " + disposition + ")");
    }

    @Override
    public boolean reload(final Session session) {
        Diag.info(log, "swap", "STUB reload: " + ctrl(session));
        notify(session, "stub: reload current identity from disk");
        return true;
    }

    @Override
    public void enterConflictShell(final Session session) {
        Diag.info(log, "swap", "STUB enterConflictShell: " + ctrl(session));
        notify(session, "stub: enter conflict spectator shell");
        session.enterConflictShell();
    }

    @Override
    public boolean resolveDeferredRestore(final Session session,
                                          final MountedIdentity baselineAsMount,
                                          final boolean resetPosition,
                                          final GameMode requestedMode) {
        Diag.info(log, "swap", "STUB resolveDeferredRestore: " + ctrl(session)
            + " -> " + id(baselineAsMount) + " resetPos=" + resetPosition
            + " mode=" + requestedMode);
        notify(session, "stub: resolve deferred restore -> "
            + id(baselineAsMount) + " (reset=" + resetPosition + " mode=" + requestedMode + ")");
        session.clearConflictShell();
        session.setCurrent(session.current(), baselineAsMount, baselineAsMount.baseline());
        return true;
    }

    // ──────────────────────────────────────────────────────────────
    //  In-game feedback
    // ──────────────────────────────────────────────────────────────

    /**
     * Send a yellow chat message to the controller if they're online.
     * Chat rather than actionbar because stub messages need to stay on
     * screen long enough for the user to read the full text.
     */
    private static void notify(final Session session, final String message) {
        if (session == null || session.current() == null) return;
        // Look up by the active ServerPlayer's UUID, not the controller
        // Mojang UUID — when on a local profile they're different.
        final Player p = Bukkit.getPlayer(session.current().getUUID());
        if (p == null || !p.isOnline()) return;
        try {
            p.sendMessage(Component.text("[ShadowCore] ", NamedTextColor.GOLD)
                .append(Component.text(message, NamedTextColor.YELLOW)));
        } catch (final RuntimeException ignored) {
            // non-fatal — the controller may have disconnected between
            // the dispatch and our notification.
        }
    }

    private static String ctrl(final Session s) {
        try {
            if (s.current() == null) return "<no-current>";
            return dev.shadowcore.core.nms.NmsCompat.profileName(s.current().getGameProfile());
        } catch (final RuntimeException e) {
            return "<?>";
        }
    }

    private static String id(final MountedIdentity i) {
        return i == null ? "null" : i.kind() + ":" + i.displayName();
    }
}
