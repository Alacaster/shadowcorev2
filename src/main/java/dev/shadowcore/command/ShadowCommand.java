package dev.shadowcore.command;

import dev.shadowcore.core.auth.AuthGateway;
import dev.shadowcore.engine.EngineEvent;
import dev.shadowcore.engine.EventEngine;
import dev.shadowcore.engine.ResponseHandle;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ShadowCommand implements TabExecutor {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final EventEngine engine;
    private final AuthGateway auth;

    public ShadowCommand(final EventEngine engine, final AuthGateway auth) {
        this.engine = engine;
        this.auth = auth;
    }

    @Override
    public boolean onCommand(@NotNull final CommandSender sender, @NotNull final Command cmd,
                             @NotNull final String label, @NotNull final String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("This command must be run by a player.");
            return true;
        }
        if (!p.hasPermission("shadowcore.admin")) {
            p.sendMessage(MM.deserialize("<red>No permission.</red>"));
            return true;
        }
        final ResponseHandle resp = miniMessage -> p.sendMessage(MM.deserialize(miniMessage));
        final UUID actor = auth.resolveControllerMojangUuid(p.getUniqueId());
        if (args.length == 0) {
            resp.reply("<yellow>Usage:</yellow> /shadow <target|logout [resetlocation]|discard|status>");
            return true;
        }
        final String first = args[0].toLowerCase();
        switch (first) {
            case "logout" -> {
                final boolean reset = args.length >= 2 && args[1].equalsIgnoreCase("resetlocation");
                engine.submit(new EngineEvent.ShadowLogout(actor, reset, resp));
            }
            case "discard" -> engine.submit(new EngineEvent.ShadowDiscard(actor, resp));
            case "status" -> engine.submit(new EngineEvent.ShadowStatus(actor, resp));
            default -> engine.submit(new EngineEvent.ShadowMount(actor, p.getName(), args[0], resp));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull final CommandSender sender, @NotNull final Command command,
                                      @NotNull final String alias, @NotNull final String[] args) {
        if (args.length == 1) {
            final List<String> base = new java.util.ArrayList<>(List.of("logout", "discard", "status"));
            Bukkit.getOnlinePlayers().forEach(p -> base.add(p.getName()));
            final String lc = args[0].toLowerCase();
            return base.stream().filter(s -> s.toLowerCase().startsWith(lc)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("logout")) {
            return List.of("resetlocation");
        }
        return List.of();
    }
}
