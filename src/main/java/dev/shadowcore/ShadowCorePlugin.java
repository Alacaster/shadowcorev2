package dev.shadowcore;

import dev.shadowcore.command.LProfileCommand;
import dev.shadowcore.command.ShadowCommand;
import dev.shadowcore.core.auth.AuthGateway;
import dev.shadowcore.core.nms.MountBackup;
import dev.shadowcore.core.swap.IdentitySwap;
import dev.shadowcore.core.swap.SessionRegistry;
import dev.shadowcore.engine.EventEngine;
import dev.shadowcore.listener.PlayerLifecycleListener;
import dev.shadowcore.listener.ShadowConflictDetector;
import dev.shadowcore.manager.Manager;
import dev.shadowcore.presentation.PresentationService;
import dev.shadowcore.store.Database;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ShadowCore 4.0 bootstrap.
 *
 * <p>The 3.0 wiring order built a 5-layer stack of
 * doll-specific NMS components (DualPlayerRegistry, PacketRouter,
 * MountKernel, PermissionSync, HeadlessConnection-aware
 * PresentationService). 4.0 replaces all of that with a thin stack:</p>
 *
 * <ol>
 *   <li>Database (orchestration only; unchanged from 3.0).</li>
 *   <li>MinecraftServer handle (NMS access for IdentitySwap).</li>
 *   <li>SessionRegistry (one Session per controller).</li>
 *   <li>MountBackup (pre-mount .dat snapshot for DISCARD path; kept from 3.0).</li>
 *   <li>IdentitySwap implementation (currently the stub; in step 2 this
 *       becomes a dispatcher that selects between ReconfigureIdentitySwap
 *       and VelocityIdentitySwap based on upstream detection).</li>
 *   <li>PresentationService (slimmed: shadow-tab-hide + skin cloak only).</li>
 *   <li>AuthGateway (kept; to be extended with multi-login detection).</li>
 *   <li>Manager, EventEngine, commands, listeners (kept; re-typed to new
 *       Session/SessionRegistry/IdentitySwap types).</li>
 * </ol>
 *
 * <p>The {@link IdentitySwap} implementation is
 * {@link dev.shadowcore.core.swap.TransferIdentitySwap}, which handles
 * profile switches and shadow mounts via transfer-reconnect. Shadow
 * sessions park the baseline {@code ServerPlayer} in a sealed bedrock
 * chamber at a remote location for the duration; see
 * {@link dev.shadowcore.core.swap.ParkingChamber}.</p>
 */
public final class ShadowCorePlugin extends JavaPlugin {
    private EventEngine engine;
    private Database db;
    private Manager manager;
    private IdentitySwap swap;
    private SessionRegistry registry;
    private PresentationService presentation;
    private AuthGateway auth;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            throw new IllegalStateException("Cannot create plugin data folder");
        }
        dev.shadowcore.util.Diag.VERBOSE = getConfig().getBoolean("verbose-diagnostics", true);
        dev.shadowcore.util.Diag.info(getLogger(), "boot",
            "onEnable: starting ShadowCore 4.0 skeleton (VERBOSE="
            + dev.shadowcore.util.Diag.VERBOSE + ")");
        diagnosePluginMode();
        diagnoseAcceptsTransfers();

        dev.shadowcore.util.Diag.trace(getLogger(), "boot", "initializing Database");
        this.db = new Database(getDataFolder(), getLogger());

        final MinecraftServer nmsServer;
        try {
            nmsServer = ((org.bukkit.craftbukkit.CraftServer) Bukkit.getServer()).getServer();
            dev.shadowcore.util.Diag.trace(getLogger(), "boot",
                "resolved MinecraftServer handle: " + nmsServer.getClass().getSimpleName());
        } catch (final RuntimeException ex) {
            dev.shadowcore.util.Diag.error(getLogger(), "boot",
                "Not running on Paper/CraftBukkit — ShadowCore cannot start.", ex);
            setEnabled(false);
            return;
        }

        dev.shadowcore.util.Diag.trace(getLogger(), "boot",
            "initializing SessionRegistry + MountBackup + PresentationService");
        this.registry = new SessionRegistry();
        // MountBackup is consumed by TransferIdentitySwap for DISCARD and
        // by future shadow machinery for pre-mount snapshots.
        final MountBackup backup = new MountBackup(getLogger(), nmsServer, getDataFolder());
        this.presentation = new PresentationService(getLogger(), registry);

        // SkinCloak is optional (ProtocolLib). Kept for future skin-override
        // use cases even though 4.0 doesn't have the 3.0 leak paths.
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            dev.shadowcore.util.Diag.info(getLogger(), "boot",
                "ProtocolLib detected — installing SkinCloak");
            try {
                final dev.shadowcore.presentation.SkinCloak cloak =
                    new dev.shadowcore.presentation.SkinCloak(getLogger(), this);
                cloak.install();
                this.presentation.attachCloak(cloak);
            } catch (final RuntimeException | LinkageError ex) {
                dev.shadowcore.util.Diag.warn(getLogger(), "boot",
                    "ProtocolLib present but SkinCloak failed to install: " + ex.getMessage());
            }
        } else {
            dev.shadowcore.util.Diag.info(getLogger(), "boot",
                "ProtocolLib absent — SkinCloak not installed.");
        }

        dev.shadowcore.util.Diag.trace(getLogger(), "boot", "initializing AuthGateway");
        this.auth = new AuthGateway(getLogger(), nmsServer, db);
        this.auth.bootstrap();

        // Skin resolver — fetches Mojang sessionserver profile properties
        // so external-identity shadow mounts can render with the correct
        // skin.
        final long ttl = getConfig().getLong("skin-cache-minutes", 60L);
        dev.shadowcore.util.Diag.trace(getLogger(), "boot",
            "initializing SkinResolver with TTL=" + ttl + "min");
        final dev.shadowcore.core.auth.SkinResolver skinResolver =
            new dev.shadowcore.core.auth.SkinResolver(getLogger(), this, ttl);

        // Pending-swap registry tracks in-flight transfer-reconnects.
        // Shared between the swap implementation (which writes records)
        // and AuthGateway (which reads them in pre-login).
        final dev.shadowcore.core.swap.PendingSwapRegistry pendingSwaps =
            new dev.shadowcore.core.swap.PendingSwapRegistry();
        this.auth.attachPendingSwaps(pendingSwaps);

        // IdentitySwap: TransferIdentitySwap handles profile switches and
        // shadow mounts via transfer-reconnect, with the parking chamber
        // managing the baseline ServerPlayer during shadow sessions.
        dev.shadowcore.util.Diag.info(getLogger(), "boot",
            "installing TransferIdentitySwap (profile + shadow swaps live)");
        final dev.shadowcore.core.swap.ParkingChamber parkingChamber =
            new dev.shadowcore.core.swap.ParkingChamber(getLogger());
        this.swap = new dev.shadowcore.core.swap.TransferIdentitySwap(
            getLogger(), nmsServer, backup, pendingSwaps, parkingChamber, db, registry);

        dev.shadowcore.util.Diag.trace(getLogger(), "boot", "initializing Manager + EventEngine");
        this.manager = new Manager(getLogger(), db, swap, registry, presentation, auth);
        this.manager.bootstrap();
        this.manager.attachSkinResolver(skinResolver);

        // Controller-bound state forwarding: op, perms, skin follow the
        // controller's Mojang UUID across every identity change. Skin only
        // forwards for profile mounts; shadow keeps the target's skin.
        dev.shadowcore.util.Diag.trace(getLogger(), "boot",
            "initializing ControllerBoundState (op + perms + skin forwarders)");
        final dev.shadowcore.core.forward.OpForwarder opFwd =
            new dev.shadowcore.core.forward.OpForwarder(getLogger());
        final dev.shadowcore.core.forward.PermissionForwarder permFwd =
            new dev.shadowcore.core.forward.PermissionForwarder(getLogger());
        permFwd.detect();
        final dev.shadowcore.core.forward.SkinForwarder skinFwd =
            new dev.shadowcore.core.forward.SkinForwarder(getLogger(), skinResolver);
        final dev.shadowcore.core.forward.ControllerBoundState forwarding =
            new dev.shadowcore.core.forward.ControllerBoundState(getLogger(), opFwd, permFwd, skinFwd);
        this.manager.attachForwarding(forwarding);

        this.engine = new EventEngine(this, manager);
        this.manager.attachEngine(engine);
        this.engine.start();

        registerCommands();
        registerListeners();

        dev.shadowcore.util.Diag.info(getLogger(), "boot",
            "ShadowCore 4.0 enabled. Profile switching is live (/lprofile switch). "
            + "Shadow operations are still stubbed pending step 5.");
    }

    private void diagnosePluginMode() {
        try {
            final java.net.URL paperPluginYml = getClassLoader().getResource("paper-plugin.yml");
            if (paperPluginYml != null) {
                getLogger().warning("paper-plugin.yml is shipped inside the jar. "
                    + "Paper prefers it over plugin.yml, loading this plugin in 'Paper plugin' mode — "
                    + "JavaPlugin#getCommand is unsupported there. Our registerCommands() uses a command-map "
                    + "fallback so /lprofile and /shadow still work, but to see correct version strings and "
                    + "clean startup, delete src/main/resources/paper-plugin.yml and rebuild.");
            }
        } catch (final Exception ignored) {}
    }

    /**
     * ShadowCore performs identity switches by sending the client a
     * {@code ClientboundTransferPacket} and letting them reconnect. Vanilla
     * rejects incoming transfer-reconnects unless {@code accepts-transfers=true}
     * is set in {@code server.properties}. Without it, every identity switch
     * would kick the player with "transfer not allowed" and never let them
     * back in.
     *
     * <p>The property is read directly from the properties file rather than
     * via any NMS field — the field name changes between Paper versions and
     * is not part of the stable API.</p>
     */
    private void diagnoseAcceptsTransfers() {
        try {
            final java.io.File propsFile = new java.io.File("server.properties");
            if (!propsFile.isFile()) {
                getLogger().warning("Could not locate server.properties at " + propsFile.getAbsolutePath()
                    + " — cannot verify accepts-transfers setting. Identity switching requires "
                    + "accepts-transfers=true in server.properties.");
                return;
            }
            final java.util.Properties props = new java.util.Properties();
            try (final java.io.FileInputStream in = new java.io.FileInputStream(propsFile)) {
                props.load(in);
            }
            final String value = props.getProperty("accepts-transfers", "false").trim();
            if (!"true".equalsIgnoreCase(value)) {
                getLogger().warning("\n"
                    + "============================================================\n"
                    + "  accepts-transfers is not set to true in server.properties.\n"
                    + "\n"
                    + "  ShadowCore performs identity switches by transfer-reconnect\n"
                    + "  via ClientboundTransferPacket. Vanilla rejects incoming\n"
                    + "  transfer-reconnects unless accepts-transfers=true.\n"
                    + "\n"
                    + "  Current value: " + value + "\n"
                    + "\n"
                    + "  Set accepts-transfers=true in server.properties and restart\n"
                    + "  the server. Until then, /lprofile switch and /shadow will\n"
                    + "  kick the player instead of switching identities.\n"
                    + "============================================================");
            } else {
                dev.shadowcore.util.Diag.info(getLogger(), "boot",
                    "accepts-transfers=true confirmed in server.properties.");
            }
        } catch (final Exception ex) {
            getLogger().warning("Could not check accepts-transfers in server.properties: " + ex.getMessage());
        }
    }

    @Override
    public void onDisable() {
        // Spec §18: "A clean plugin disable operation must never leave the
        // server in a plugin-only shell state. … every deferred restore
        // operation must resolve to a normal baseline mount before control
        // returns to vanilla-only behavior."
        //
        // In the 4.0 skeleton (step 1), no world-side mounts actually
        // happen, so there is nothing to resolve — we just stop the engine
        // and close the DB. Once step 2+ wire the real IdentitySwap,
        // this method will also walk active shell sessions and resolve them
        // via the real swap paths before shutdown.
        if (engine != null) engine.stop();
        if (db != null) db.close();
        getLogger().info("ShadowCore disabled.");
    }

    private void registerCommands() {
        final LProfileCommand lpc = new LProfileCommand(engine, db, auth);
        final ShadowCommand sc = new ShadowCommand(engine, auth);
        registerOne("lprofile", lpc, lpc, "Manage local profiles.",
            java.util.List.of("localprofile", "lp"));
        registerOne("shadow", sc, sc, "Mount another identity as a shadow session.",
            java.util.List.of());
    }

    @SuppressWarnings("unchecked")
    private void registerOne(final String name,
                             final org.bukkit.command.CommandExecutor executor,
                             final org.bukkit.command.TabCompleter completer,
                             final String description,
                             final java.util.List<String> aliases) {
        PluginCommand plugged = null;
        try {
            plugged = getCommand(name);
        } catch (final UnsupportedOperationException paperMode) {
            getLogger().fine("Paper plugin mode detected — registering '" + name + "' via command map.");
        }
        if (plugged != null) {
            plugged.setExecutor(executor);
            plugged.setTabCompleter(completer);
            return;
        }
        try {
            final var ctor = PluginCommand.class.getDeclaredConstructor(String.class, org.bukkit.plugin.Plugin.class);
            ctor.setAccessible(true);
            final PluginCommand cmd = ctor.newInstance(name, this);
            cmd.setDescription(description);
            cmd.setAliases(aliases);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(completer);
            getServer().getCommandMap().register(getName().toLowerCase(java.util.Locale.ROOT), cmd);
            getLogger().info("Registered /" + name + " via command-map fallback.");
        } catch (final ReflectiveOperationException ex) {
            getLogger().severe("Failed to register /" + name + " via command-map fallback: " + ex.getMessage());
        }
    }

    private void registerListeners() {
        final var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerLifecycleListener(
            this, getLogger(), engine, registry, presentation, manager, auth), this);
        pm.registerEvents(new ShadowConflictDetector(engine, manager), this);
        pm.registerEvents(auth, this);
    }

    // Accessors for other subsystems / future test plumbing.
    public EventEngine engine() { return engine; }
    public Database db() { return db; }
    public Manager manager() { return manager; }
    public IdentitySwap swap() { return swap; }
    public SessionRegistry registry() { return registry; }
    public PresentationService presentation() { return presentation; }
    public AuthGateway auth() { return auth; }
}
