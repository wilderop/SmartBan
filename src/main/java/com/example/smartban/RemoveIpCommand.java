package com.example.smartban;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.UUID;

public class RemoveIpCommand implements CommandExecutor {

    private final SmartBanPlugin plugin;

    public RemoveIpCommand(SmartBanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("smartban.ipmanage")) {
            sender.sendMessage("No permission.");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage("Usage: /removeip <player> <ip>");
            return true;
        }

        UUID targetUuid = plugin.getAltManager().getUuidFromName(args[0]);
        if (targetUuid == null) {
            sender.sendMessage("Player has never joined.");
            return true;
        }

        String ip = args[1];
        plugin.getAltManager().removeIp(targetUuid, ip);
        sender.sendMessage("Removed IP " + ip + " from " + args[0] + ".");
        return true;
    }
}
