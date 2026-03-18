package com.example.smartban;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

public class LoginListener implements Listener {

    private final SmartBanPlugin plugin;

    public LoginListener(SmartBanPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress().getHostAddress();
        String name = event.getName();
        UUID uuid = event.getUniqueId();

        // Fast path: immediate ban checks (no DB wait)
        if (plugin.getBanManager().isBanned(uuid)) {
            long remainingHours = plugin.getBanManager().getRemainingBanTime(uuid) / 3600000;
            String reason = plugin.getBanManager().getBanReason(uuid);
            String kickMsg = plugin.getPluginConfig().getString("kick_message", "You are banned for {remaining} more hours. Reason: {reason}")
                    .replace("{remaining}", String.valueOf(remainingHours))
                    .replace("{reason}", reason != null ? reason : "No reason provided");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, kickMsg);
            return;
        }

        if (plugin.getBanManager().isIpBanned(ip)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, "Your IP is banned.");
            return;
        }

        // Queue all heavy work (name update, IP add, alt linking) — non-blocking
        plugin.queueDbWrite(() -> {
            Connection conn = null;
            try {
                conn = plugin.getConnection();
                conn.setAutoCommit(false);

                // Update name
                plugin.getAltManager().updatePlayerName(uuid, name);

                // Add IP to this player
                plugin.getAltManager().addIp(uuid, ip);

                // Add IP to all alts
                Set<UUID> alts = plugin.getAltManager().getAllAlts(uuid);
                for (UUID alt : alts) {
                    if (!alt.equals(uuid)) {
                        plugin.getAltManager().addIp(alt, ip);
                    }
                }

                // Auto-link based on shared IP
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT uuid FROM ips WHERE ip = ? AND uuid != ?")) {
                    stmt.setString(1, ip);
                    stmt.setString(2, uuid.toString());
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        UUID otherUuid = UUID.fromString(rs.getString("uuid"));
                        plugin.getAltManager().linkAlts(uuid, otherUuid);
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                if (conn != null) {
                    try {
                        conn.rollback();
                    } catch (SQLException ignored) {}
                }
                plugin.getLogger().severe("Queued DB error during login for " + name + " (IP: " + ip + "): " + e.getMessage());
                e.printStackTrace();
            } finally {
                if (conn != null) {
                    try {
                        conn.setAutoCommit(true);
                        conn.close();
                    } catch (SQLException ignored) {}
                }
            }
        });
    }
}
