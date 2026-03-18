package com.example.smartban;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class AltListCommand implements CommandExecutor {

    private final SmartBanPlugin plugin;

    public AltListCommand(SmartBanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("smartban.altmanage")) {
            sender.sendMessage("No permission.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage("Usage: /altlist <player>");
            return true;
        }

        UUID targetUuid = plugin.getAltManager().getUuidFromName(args[0]);
        if (targetUuid == null) {
            sender.sendMessage("Player has never joined.");
            return true;
        }

        Set<UUID> alts = plugin.getAltManager().getAllAlts(targetUuid);
        String altsStr = alts.stream().map(plugin.getAltManager()::getPlayerName).collect(Collectors.joining(", "));
        sender.sendMessage("Alts for " + args[0] + ": " + altsStr);
        return true;
    }
}
