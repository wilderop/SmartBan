package com.example.smartban;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class UnbanCommand implements CommandExecutor {

    private final SmartBanPlugin plugin;

    public UnbanCommand(SmartBanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("smartban.unban")) {
            sender.sendMessage("No permission.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /unban <player> <reason...>");
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

        plugin.getBanManager().unbanPlayer(targetUuid, staffUuid, reason);
        sender.sendMessage("Unbanned " + playerName + ".");
        return true;
    }
}
