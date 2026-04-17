package dev.shadowcore;

import dev.shadowcore.command.LProfileCommand;
import dev.shadowcore.command.ShadowCommand;
import dev.shadowcore.core.auth.AuthGateway;
import dev.shadowcore.core.nms.DualPlayerRegistry;
import dev.shadowcore.core.nms.MountBackup;
import dev.shadowcore.core.nms.MountKernel;
import dev.shadowcore.core.nms.PacketRouter;
import dev.shadowcore.engine.EventEngine;
import dev.shadowcore.listener.PlayerLifecycleListener;
import dev.shadowcore.listener.ShadowConflictDetector;
import dev.shadowcore.manager.Manager;
import dev.shadowcore.presentation.PresentationService;
import dev.shadowcore.store.Database;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bootstraps ShadowCore.
 *
 * <p>Wiring order — each step is dependency-first:</p>
 * <ol>
 *   <li>Data dir + SQLite orchestration store</li>
 *   <li>MinecraftServer handle (for NMS access)</li>
 *   <li>Registry + PacketRouter + MountBackup</li>
 *   <li>MountKernel (needs all of the above)</li>
 *   <li>PresentationService</li>
 *   <li>AuthGateway (needs DB + server for profile-cache priming)</li>
 *   <li>Manager (wires everything together)</li>
 *   <li>EventEngine (runs the manager's dispatch on main thread)</li>
 *   <li>Command executors</li>
 *   <li>Bukkit listeners (lifecycle + conflict detector + auth gateway)</li>
 * </ol>
 */
public final class ShadowCorePlugin extends JavaPlugin {
    private EventEngine engine;
    private Database db;
    private Manager manager;
    private MountKernel kernel;
    private DualPlayerRegistry registry;
    private PacketRouter router;
    private PresentationService presentation;
    private AuthGateway auth;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            throw new IllegalStateException("Cannot create plugin data folder");
        }

        this.db = new Database(getDataFolder(), getLogger());

        final MinecraftServer nmsServer;
        try {
            nmsServer = ((org.bukkit.craftbukkit.CraftServer) Bukkit.getServer()).getServer();
        } catch (final RuntimeException ex) {
            getLogger().severe("Not running on Paper/CraftBukkit — ShadowCore cannot start.");
            setEnabled(false);
            return;
        }

        this.registry = new DualPlayerRegistry();
        this.router = new PacketRouter(getLogger(), registry);
        final MountBackup backup = new MountBackup(getLogger(), nmsServer, getDataFolder());
        this.kernel = new MountKernel(getLogger(), nmsServer, registry, router, backup);
        this.presentation = new PresentationService(getLogger(), registry);

        // Wire SkinCloak (cheat-client resistance for PHANTOM mode) iff
        // ProtocolLib is present. The plugin functions without it: PHANTOM
        // still hides baseline from tab and applies world invisibility;
        // SkinCloak adds scrubbing of any leaked PlayerInfo entry.
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            try {
                final dev.shadowcore.presentation.SkinCloak cloak =
                    new dev.shadowcore.presentation.SkinCloak(getLogger(), this);
                cloak.install();
                this.presentation.attachCloak(cloak);
            } catch (final RuntimeException | LinkageError ex) {
                getLogger().warning("ProtocolLib present but SkinCloak failed to install: "
                    + ex.getMessage() + " — PHANTOM mode will work without identity scrubbing.");
            }
        } else {
            getLogger().info("ProtocolLib absent — PHANTOM mode active without hack-client identity scrubbing.");
        }

        this.auth = new AuthGateway(getLogger(), nmsServer, db);
        this.auth.bootstrap();

        // Skin resolver — fetches Mojang sessionserver profile properties
        // (texture + signature) so shadow dolls can render as the target's
        // real skin rather than default Steve/Alex. Cached in-memory with a
        // configurable TTL.
        final dev.shadowcore.core.auth.SkinResolver skinResolver =
            new dev.shadowcore.core.auth.SkinResolver(
                getLogger(), this,
                getConfig().getLong("skin-cache-minutes", 60L));
        this.kernel.attachSkinResolver(skinResolver);

        this.manager = new Manager(getLogger(), db, kernel, registry, presentation, auth);
        this.manager.bootstrap();
        this.manager.attachSkinResolver(skinResolver);

        this.engine = new EventEngine(this, manager);
        this.manager.attachEngine(engine);
        this.engine.start();

        registerCommands();
        registerListeners();

        getLogger().info("ShadowCore 3.0 enabled.");
    }

    @Override
    public void onDisable() {
        // Spec §18 — "A clean plugin disable operation must never leave the
        // server in a plugin-only shell state. … every deferred restore
        // operation must resolve to a normal baseline mount before control
        // returns to vanilla-only behavior."
        if (engine != null) engine.stop();
        resolveEveryShell();
        // Final pass: persist every mounted identity through vanilla save,
        // then remove the mounted-side so vanilla's own world save on
        // shutdown doesn't double-tick us.
        if (kernel != null && registry != null) {
            for (final var s : registry.all()) {
                if (s.mounted() != null && s.mounted() != s.controller()) {
                    try { kernel.persist(s); } catch (final RuntimeException ignored) {}
                    try { kernel.unmount(s, dev.shadowcore.model.MountDisposition.COMMIT); } catch (final RuntimeException ignored) {}
                }
            }
        }
        if (db != null) db.close();
        getLogger().info("ShadowCore disabled.");
    }

    /**
     * Resolve every shell-state session back to its baseline synchronously.
     * Called only from onDisable; the engine has already been stopped so we
     * operate directly on the kernel.
     */
    private void resolveEveryShell() {
        if (kernel == null || registry == null || db == null || manager == null) return;
        for (final var session : registry.all()) {
            if (!session.conflictShell()) continue;
            try {
                final var sess = db.loadSession(session.controllerUuid()).orElse(null);
                if (sess == null) continue;
                final dev.shadowcore.model.ProfileIdentity baseline =
                    (sess.deferredRestoreMain() || sess.deferredRestoreProfileUuid() == null)
                        ? dev.shadowcore.model.ProfileIdentity.main(session.controllerUuid())
                        : db.findProfileByUuid(sess.deferredRestoreProfileUuid())
                            .map(p -> dev.shadowcore.model.ProfileIdentity.local(
                                session.controllerUuid(), p.profileUuid(), p.suffix()))
                            .orElse(dev.shadowcore.model.ProfileIdentity.main(session.controllerUuid()));
                final String baseName = db.getAccountName(session.controllerUuid()).orElse("player");
                final String display = baseline.isMain() ? baseName
                    : dev.shadowcore.util.Naming.reconstructDisplayName(baseName, baseline.suffix());
                final var restored = dev.shadowcore.model.MountedIdentity.ofBaseline(baseline, display);
                kernel.resolveDeferredRestore(session, restored, true, org.bukkit.GameMode.SURVIVAL);
                db.saveSession(new dev.shadowcore.store.Database.SessionRecord(
                    session.controllerUuid(),
                    baseline.isMain() ? null : baseline.profileUuid(),
                    null, null, false, false, null, false, true, null));
            } catch (final RuntimeException ex) {
                getLogger().warning("Could not resolve shell for " + session.controllerUuid()
                    + " on disable: " + ex.getMessage());
            }
        }
    }

    private void registerCommands() {
        final PluginCommand lp = Objects.requireNonNull(getCommand("lprofile"), "missing 'lprofile' command");
        final LProfileCommand lpc = new LProfileCommand(engine, db);
        lp.setExecutor(lpc); lp.setTabCompleter(lpc);

        final PluginCommand sh = Objects.requireNonNull(getCommand("shadow"), "missing 'shadow' command");
        final ShadowCommand sc = new ShadowCommand(engine);
        sh.setExecutor(sc); sh.setTabCompleter(sc);
    }

    private void registerListeners() {
        final var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerLifecycleListener(
            this, getLogger(), engine, registry, router, presentation, manager), this);
        pm.registerEvents(new ShadowConflictDetector(engine, manager), this);
        pm.registerEvents(auth, this);
    }

    public EventEngine engine() { return engine; }
    public Database db() { return db; }
    public Manager manager() { return manager; }
    public MountKernel kernel() { return kernel; }
    public DualPlayerRegistry registry() { return registry; }
    public PresentationService presentation() { return presentation; }
    public AuthGateway auth() { return auth; }
}
