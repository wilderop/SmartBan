package com.example.smartban;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class AltLinkCommand implements CommandExecutor {

    private final SmartBanPlugin plugin;

    public AltLinkCommand(SmartBanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("smartban.altmanage")) {
            sender.sendMessage("No permission.");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage("Usage: /altlink <player1> <player2>");
            return true;
        }

        java.util.UUID uuid1 = plugin.getAltManager().getUuidFromName(args[0]);
        java.util.UUID uuid2 = plugin.getAltManager().getUuidFromName(args[1]);
        if (uuid1 == null || uuid2 == null) {
            sender.sendMessage("One or both players have never joined.");
            return true;
        }

        plugin.getAltManager().linkAlts(uuid1, uuid2);
        sender.sendMessage("Linked " + args[0] + " and " + args[1] + " as alts.");
        return true;
    }
}
