package com.example.smartban;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Set;
import java.util.UUID;

public class IpListCommand implements CommandExecutor {

    private final SmartBanPlugin plugin;

    public IpListCommand(SmartBanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("smartban.ipmanage")) {
            sender.sendMessage("No permission.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage("Usage: /iplist <player>");
            return true;
        }

        UUID targetUuid = plugin.getAltManager().getUuidFromName(args[0]);
        if (targetUuid == null) {
            sender.sendMessage("Player has never joined.");
            return true;
        }

        Set<String> ips = plugin.getAltManager().getIps(targetUuid);
        sender.sendMessage("IPs for " + args[0] + ": " + String.join(", ", ips));
        return true;
    }
}
