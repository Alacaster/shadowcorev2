package dev.shadowcore.command;

import dev.shadowcore.core.auth.AuthGateway;
import dev.shadowcore.engine.EngineEvent;
import dev.shadowcore.engine.EventEngine;
import dev.shadowcore.engine.ResponseHandle;
import dev.shadowcore.store.Database;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Entry point for {@code /lprofile}. Every branch builds an
 * {@link EngineEvent} and submits it to the engine. No business logic here —
 * the manager owns state transitions.
 *
 * <p>Engine events use the controller's <b>Mojang UUID</b> as the actor,
 * never the active ServerPlayer's UUID. When the player runs a command
 * while on a local profile, {@code player.getUniqueId()} returns the
 * profile's synthetic UUID; we resolve to the Mojang UUID via
 * {@link AuthGateway#resolveControllerMojangUuid} before dispatching.</p>
 */
public final class LProfileCommand implements TabExecutor {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final EventEngine engine;
    private final Database db;
    private final AuthGateway auth;

    public LProfileCommand(final EventEngine engine, final Database db, final AuthGateway auth) {
        this.engine = engine;
        this.db = db;
        this.auth = auth;
    }

    @Override
    public boolean onCommand(@NotNull final CommandSender sender, @NotNull final Command cmd,
                             @NotNull final String label, @NotNull final String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("This command must be run by a player.");
            return true;
        }
        final ResponseHandle resp = reply(p);
        final UUID actor = auth.resolveControllerMojangUuid(p.getUniqueId());
        if (args.length == 0) {
            resp.reply("<yellow>Usage:</yellow> /lprofile <create|switch|list|rename|delete|status|discard|logout|main|setlimit|admindelete> ...");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "create" -> {
                if (args.length < 2) { resp.reply("<red>Usage: /lprofile create <suffix></red>"); return true; }
                engine.submit(new EngineEvent.ProfileCreate(actor, p.getName(), args[1], resp));
            }
            case "switch" -> {
                if (args.length < 2) { resp.reply("<red>Usage: /lprofile switch <suffix></red>"); return true; }
                engine.submit(new EngineEvent.ProfileSwitch(actor, args[1], resp));
            }
            case "list" -> engine.submit(new EngineEvent.ProfileList(actor, resp));
            case "rename" -> {
                if (args.length < 3) { resp.reply("<red>Usage: /lprofile rename <old> <new></red>"); return true; }
                engine.submit(new EngineEvent.ProfileRename(actor, args[1], args[2], resp));
            }
            case "delete" -> {
                if (args.length < 2) { resp.reply("<red>Usage: /lprofile delete <suffix></red>"); return true; }
                engine.submit(new EngineEvent.ProfileDelete(actor, args[1], resp));
            }
            case "status" -> engine.submit(new EngineEvent.ProfileStatus(actor, resp));
            case "discard" -> {
                if (!p.hasPermission("shadowcore.admin")) { resp.reply("<red>No permission.</red>"); return true; }
                engine.submit(new EngineEvent.ProfileDiscard(actor, resp));
            }
            case "logout", "main" -> engine.submit(new EngineEvent.ProfileReturnToMain(actor, resp));
            case "setlimit" -> {
                if (args.length < 3) { resp.reply("<red>Usage: /lprofile setlimit <player> <limit></red>"); return true; }
                final Optional<UUID> target = playerUuid(args[1]);
                if (target.isEmpty()) { resp.reply("<red>Unknown player.</red>"); return true; }
                final int limit;
                try { limit = Integer.parseInt(args[2]); } catch (final NumberFormatException ex) {
                    resp.reply("<red>Limit must be a number.</red>"); return true;
                }
                engine.submit(new EngineEvent.ProfileSetLimit(actor, target.get(), limit, resp));
            }
            case "admindelete" -> {
                if (args.length < 3) { resp.reply("<red>Usage: /lprofile admindelete <player> <suffix></red>"); return true; }
                final Optional<UUID> target = playerUuid(args[1]);
                if (target.isEmpty()) { resp.reply("<red>Unknown player.</red>"); return true; }
                engine.submit(new EngineEvent.ProfileAdminDelete(actor, target.get(), args[2], resp));
            }
            default -> resp.reply("<red>Unknown subcommand.</red>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull final CommandSender sender, @NotNull final Command command,
                                      @NotNull final String alias, @NotNull final String[] args) {
        if (args.length == 1) {
            final List<String> base = new ArrayList<>(List.of(
                "create", "switch", "list", "rename", "delete", "status", "logout", "main"));
            if (sender.hasPermission("shadowcore.admin")) {
                base.add("discard"); base.add("setlimit"); base.add("admindelete");
            }
            return filter(base, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("switch")
                              || args[0].equalsIgnoreCase("rename")
                              || args[0].equalsIgnoreCase("delete"))) {
            if (sender instanceof Player p) {
                final UUID actor = auth.resolveControllerMojangUuid(p.getUniqueId());
                return filter(
                    db.listProfiles(actor).stream().map(Database.ProfileRecord::suffix).toList(),
                    args[1]
                );
            }
        }
        return List.of();
    }

    private ResponseHandle reply(final Player p) {
        return miniMessage -> p.sendMessage(MM.deserialize(miniMessage));
    }

    private Optional<UUID> playerUuid(final String name) {
        final Player online = Bukkit.getPlayerExact(name);
        if (online != null) return Optional.of(online.getUniqueId());
        final var offline = Bukkit.getOfflinePlayer(name);
        return Optional.ofNullable(offline.getUniqueId());
    }

    private static List<String> filter(final List<String> src, final String prefix) {
        final String lc = prefix.toLowerCase();
        return src.stream().filter(s -> s.toLowerCase().startsWith(lc)).toList();
    }
}
