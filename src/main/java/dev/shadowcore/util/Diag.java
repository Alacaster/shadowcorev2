package dev.shadowcore.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central toggle for verbose diagnostic logging.
 *
 * <p>ShadowCore operates in the NMS layer where bugs are frequently silent
 * — a misrouted packet, a stale cache, a skipped update — and the only way
 * to diagnose them is to see the machine's decisions as they happen.
 * During 3.0 development we're running with {@link #VERBOSE} set so that
 * every mount, every packet routing decision, every permission sync
 * emits a trace. Once the plugin is stable this can be flipped off (or
 * driven by config), and later the call sites themselves can be stripped.</p>
 *
 * <h2>Convention</h2>
 * Every call site in this codebase that emits Diag output begins with a
 * short tag in square brackets identifying the subsystem:
 * <pre>
 *   [mount]   lifecycle of MountKernel
 *   [router]  inbound/outbound packet routing decisions
 *   [hconn]   HeadlessConnection send/tick/forward
 *   [perms]   permission/gamemode sync
 *   [skin]    SkinResolver / SkinCloak
 *   [nms]     NmsCompat reflective operations
 *   [plist]   PlayerListAccess / dual-player registry changes
 *   [backup]  MountBackup capture/restore/drop
 *   [auth]    AuthGateway
 *   [mgr]     Manager state machine transitions
 *   [db]      Database read/write
 *   [life]    PlayerLifecycleListener
 * </pre>
 *
 * <p>Searching the codebase for "Diag." finds every diagnostic emission
 * for a clean removal pass when they're no longer needed.</p>
 *
 * <h2>Cost</h2>
 * When {@link #VERBOSE} is false, every Diag call is a single boolean
 * load + branch (the varargs construction and string concatenation at
 * the call site still happens, but the log formatting doesn't). Not zero
 * cost, but negligible for an NMS plugin where a handful of hot packet
 * paths matter and the rest are rare transitions.
 */
public final class Diag {
    /**
     * Master toggle. Flip to {@code false} to silence all diagnostic
     * output without recompiling call sites. For config-driven control,
     * the plugin's onEnable can set this from {@code config.yml}.
     */
    public static volatile boolean VERBOSE = true;

    private Diag() {}

    /** Info-level diagnostic: routine lifecycle events. */
    public static void info(final Logger log, final String tag, final String msg) {
        if (!VERBOSE) return;
        log.info("[SC:" + tag + "] " + msg);
    }

    /** Fine-grained trace: packet-by-packet, tick-by-tick detail. */
    public static void trace(final Logger log, final String tag, final String msg) {
        if (!VERBOSE) return;
        // Use INFO level so operators see it without JUL config tweaks.
        // When we graduate to production, flip VERBOSE off or drop this
        // to log.fine(...) and re-enable "shadowcore" log levels in config.
        log.info("[SC:" + tag + ":trace] " + msg);
    }

    /** Warning: something unexpected but recoverable. */
    public static void warn(final Logger log, final String tag, final String msg) {
        log.warning("[SC:" + tag + "] " + msg);
    }

    /** Error with exception attached. Always logged regardless of VERBOSE. */
    public static void error(final Logger log, final String tag, final String msg, final Throwable t) {
        log.log(Level.SEVERE, "[SC:" + tag + "] " + msg, t);
    }

    /** Error without exception. Always logged. */
    public static void error(final Logger log, final String tag, final String msg) {
        log.severe("[SC:" + tag + "] " + msg);
    }
}
