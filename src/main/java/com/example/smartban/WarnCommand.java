package com.example.smartban;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class WarnCommand implements CommandExecutor {

    private final SmartBanPlugin plugin;

    public WarnCommand(SmartBanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("smartban.warn")) {
            sender.sendMessage("No permission.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("Usage: /warn <player> <reason...>");
            return true;
        }

        String playerName = args[0];
        UUID targetUuid = plugin.getAltManager().getUuidFromName(playerName);
        if (targetUuid == null) {
            sender.sendMessage("Player has never joined the server.");
            return true;
        }

        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));

        UUID staffUuid = (sender instanceof Player) ? ((Player) sender).getUniqueId() : UUID.randomUUID();

        // Record as a "warning" (duration = 0)
        plugin.getBanManager().recordWarning(targetUuid, staffUuid, reason);

        sender.sendMessage("Warned " + playerName + " for: " + reason);

        // Optional: notify online staff
        if (plugin.getPluginConfig().getBoolean("broadcast_warnings", true)) {
            String msg = "§e[Warning] §f" + playerName + " was warned by " + sender.getName() + ": " + reason;
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.hasPermission("smartban.notify")) {
                    p.sendMessage(msg);
                }
            }
        }

        return true;
    }
}
