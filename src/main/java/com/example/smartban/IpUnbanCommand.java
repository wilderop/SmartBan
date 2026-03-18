package com.example.smartban;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class IpUnbanCommand implements CommandExecutor {

    private final SmartBanPlugin plugin;

    public IpUnbanCommand(SmartBanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("smartban.ipmanage")) {
            sender.sendMessage("No permission.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /ipunban <ip> <reason...>");
            return true;
        }

        String ip = args[0];
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));

        plugin.getBanManager().unbanIp(ip, reason);
        sender.sendMessage("Unbanned IP " + ip + ".");
        return true;
    }
}
