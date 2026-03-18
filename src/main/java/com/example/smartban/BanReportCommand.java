package com.example.smartban;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class BanReportCommand implements CommandExecutor {

    private final SmartBanPlugin plugin;

    public BanReportCommand(SmartBanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("smartban.report")) {
            sender.sendMessage("No permission.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage("Usage: /banreport <player>");
            return true;
        }

        String playerName = args[0];
        java.util.UUID targetUuid = plugin.getAltManager().getUuidFromName(playerName);
        if (targetUuid == null) {
            sender.sendMessage("Player has never joined the server.");
            return true;
        }

        java.util.List<String> report = plugin.getBanManager().getBanReport(targetUuid);
        report.forEach(sender::sendMessage);
        return true;
    }
}
