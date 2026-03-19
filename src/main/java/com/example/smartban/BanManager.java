package com.example.smartban;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class BanManager {

    private final SmartBanPlugin plugin;
    private final AltManager altManager;

    public BanManager(SmartBanPlugin plugin, AltManager altManager) {
        this.plugin = plugin;
        this.altManager = altManager;
    }

    public void banPlayer(UUID targetUuid, UUID staffUuid, String reason) {
        Set<UUID> alts = altManager.getAllAlts(targetUuid);
        int banCount = getActiveBanCountForGroup(alts);
        int durationHours = plugin.getPluginConfig().getInt("default_min_ban_hours", 6) * (int) Math.pow(2, banCount);

        long now = System.currentTimeMillis();

        // Record ban for target + alts
        for (UUID uuid : alts) {
            recordBanOrWarn(uuid, staffUuid, now, durationHours, reason, "BAN");
        }

        // Kick all online alts immediately
        for (UUID uuid : alts) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                String kickMsg = plugin.getPluginConfig().getString("kick_message", "You are banned for {remaining} more hours. Reason: {reason}")
                        .replace("{remaining}", String.valueOf(durationHours))
                        .replace("{reason}", reason != null ? reason : "No reason provided");
                player.kickPlayer(kickMsg);
            }
        }

        // Build broadcast message with real data
        String playerName = altManager.getPlayerName(targetUuid);
        String staffName = altManager.getPlayerName(staffUuid);
        String altsStr = alts.stream()
                .map(altManager::getPlayerName)
                .collect(Collectors.joining(", "));

        Set<String> allIps = new HashSet<>();
        for (UUID uuid : alts) {
            allIps.addAll(altManager.getIps(uuid));
        }
        String ipsStr = allIps.isEmpty() ? "None" : String.join(", ", allIps);

        String broadcast = plugin.getPluginConfig().getString("broadcast_format", "[Staff] {player} was banned for {duration} hours by {staff}: {reason} (Alts: {alts}, IPs: {ips})")
                .replace("{player}", playerName)
                .replace("{duration}", String.valueOf(durationHours))
                .replace("{staff}", staffName)
                .replace("{reason}", reason)
                .replace("{alts}", altsStr)
                .replace("{ips}", ipsStr);

        // Send only to staff (smartban.notify permission)
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("smartban.notify")) {
                p.sendMessage(broadcast);
            }
        }
    }

    public void recordWarning(UUID targetUuid, UUID staffUuid, String reason) {
        Set<UUID> alts = altManager.getAllAlts(targetUuid);
        long now = System.currentTimeMillis();

        for (UUID uuid : alts) {
            recordBanOrWarn(uuid, staffUuid, now, 0, reason, "WARN");
        }

        String staffName = altManager.getPlayerName(staffUuid);
        String playerName = altManager.getPlayerName(targetUuid);
        String msg = "§e[Warning] §f" + playerName + " was warned by " + staffName + ": " + reason;

        // Send only to staff
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("smartban.notify")) {
                p.sendMessage(msg);
            }
        }
    }

    private void recordBanOrWarn(UUID uuid, UUID staffUuid, long timestamp, int durationHours, String reason, String actionType) {
        int maxRetries = 5;
        long delayMs = 100;
        boolean success = false;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            Connection conn = null;
            try {
                conn = plugin.getConnection();
                conn.setAutoCommit(false);

                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO bans (uuid, staff_uuid, timestamp, duration_hours, reason, active, action_type) " +
                        "VALUES (?, ?, ?, ?, ?, TRUE, ?)")) {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, staffUuid.toString());
                    stmt.setLong(3, timestamp);
                    stmt.setInt(4, durationHours);
                    stmt.setString(5, reason);
                    stmt.setString(6, actionType);
                    stmt.executeUpdate();
                }

                conn.commit();
                success = true;
                break;
            } catch (SQLException e) {
                if (conn != null) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                }
                if (e.getErrorCode() != 90031 && e.getErrorCode() != 23505) {
                    plugin.getLogger().severe("Failed to record " + actionType + " for " + uuid + ": " + e.getMessage());
                    return;
                }
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                delayMs *= 2;
            } finally {
                if (conn != null) {
                    try {
                        conn.setAutoCommit(true);
                        conn.close();
                    } catch (SQLException ignored) {}
                }
            }
        }
        if (!success) {
            plugin.getLogger().severe("Failed to record " + actionType + " after retries for " + uuid);
        }
    }

    public void unbanPlayer(UUID targetUuid, UUID staffUuid, String reason) {
        Set<UUID> alts = altManager.getAllAlts(targetUuid);
        long now = System.currentTimeMillis();

        for (UUID uuid : alts) {
            try (Connection conn = plugin.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE bans SET active = FALSE WHERE uuid = ? AND active = TRUE")) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to unban " + uuid + ": " + e.getMessage());
            }

            try (Connection conn = plugin.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO pardons (ban_id, staff_uuid, timestamp, reason) " +
                         "SELECT id, ?, ?, ? FROM bans WHERE uuid = ? ORDER BY id DESC LIMIT 1")) {
                stmt.setString(1, staffUuid.toString());
                stmt.setLong(2, now);
                stmt.setString(3, reason);
                stmt.setString(4, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to record pardon for " + uuid + ": " + e.getMessage());
            }
        }
    }

    public boolean isBanned(UUID uuid) {
        try (Connection conn = plugin.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT 1 FROM bans WHERE uuid = ? AND active = TRUE " +
                     "AND (duration_hours = 0 OR timestamp + duration_hours * 3600000 > ?) LIMIT 1")) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, System.currentTimeMillis());
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to check ban status: " + e.getMessage());
            return false;
        }
    }

    public long getRemainingBanTime(UUID uuid) {
        try (Connection conn = plugin.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT timestamp + duration_hours * 3600000 - ? AS remaining " +
                     "FROM bans WHERE uuid = ? AND active = TRUE AND duration_hours > 0 " +
                     "ORDER BY id DESC LIMIT 1")) {
            stmt.setLong(1, System.currentTimeMillis());
            stmt.setString(2, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return Math.max(0, rs.getLong("remaining"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get remaining ban time: " + e.getMessage());
        }
        return 0;
    }

    public String getBanReason(UUID uuid) {
        try (Connection conn = plugin.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT reason FROM bans WHERE uuid = ? AND active = TRUE ORDER BY id DESC LIMIT 1")) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("reason");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get ban reason: " + e.getMessage());
        }
        return null;
    }

    public int getActiveBanCountForGroup(Set<UUID> group) {
        int count = 0;
        try (Connection conn = plugin.getConnection()) {
            String placeholders = String.join(",", Collections.nCopies(group.size(), "?"));
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM bans WHERE uuid IN (" + placeholders + ") AND active = TRUE AND action_type = 'BAN'")) {
                int i = 1;
                for (UUID u : group) {
                    stmt.setString(i++, u.toString());
                }
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    count = rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to count active bans: " + e.getMessage());
        }
        return count;
    }

    public boolean isIpBanned(String ip) {
        try (Connection conn = plugin.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT 1 FROM ip_bans WHERE ip = ? AND active = TRUE " +
                     "AND (duration_hours = 0 OR timestamp + duration_hours * 3600000 > ?) LIMIT 1")) {
            stmt.setString(1, ip);
            stmt.setLong(2, System.currentTimeMillis());
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to check IP ban status for " + ip + ": " + e.getMessage());
            return false;
        }
    }

    public void unbanIp(String ip, String reason) {
        try (Connection conn = plugin.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE ip_bans SET active = FALSE WHERE ip = ? AND active = TRUE")) {
            stmt.setString(1, ip);
            stmt.executeUpdate();
            plugin.getLogger().info("IP " + ip + " was unbanned (reason: " + reason + ")");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to unban IP " + ip + ": " + e.getMessage());
        }
    }

    public List<String> getBanReport(UUID targetUuid) {
        List<String> report = new ArrayList<>();
        Set<UUID> alts = altManager.getAllAlts(targetUuid);
        String altsStr = alts.stream().map(altManager::getPlayerName).collect(Collectors.joining(", "));

        Set<String> allIps = new HashSet<>();
        for (UUID uuid : alts) {
            allIps.addAll(altManager.getIps(uuid));
        }
        String ipsStr = allIps.isEmpty() ? "None" : String.join(", ", allIps);

        report.add("§6Ban/Warn Report for §e" + altManager.getPlayerName(targetUuid));
        report.add("§7Alts: §f" + altsStr);
        report.add("§7IPs: §f" + ipsStr);
        report.add("§7Current Status: " + (isBanned(targetUuid) ?
                "§cBanned (Remaining: " + (getRemainingBanTime(targetUuid) / 3600000) + " hours)" : "§aNot Banned"));

        Map<Long, String> history = new TreeMap<>();

        try (Connection conn = plugin.getConnection()) {
            // Bans & Warnings
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, uuid, staff_uuid, timestamp, duration_hours, reason, active, action_type " +
                    "FROM bans WHERE uuid = ? ORDER BY timestamp DESC")) {
                for (UUID uuid : alts) {
                    stmt.setString(1, uuid.toString());
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        long ts = rs.getLong("timestamp");
                        String type = rs.getString("action_type");
                        String entry = (type.equals("WARN") ? "§eWarning" : "§cBan") +
                                " §7[" + new Timestamp(ts) + "] on §f" +
                                altManager.getPlayerName(UUID.fromString(rs.getString("uuid"))) +
                                " §7by §f" + altManager.getPlayerName(UUID.fromString(rs.getString("staff_uuid"))) +
                                (type.equals("WARN") ? "" : " §7for §f" + rs.getInt("duration_hours") + " hours") +
                                "§7: §f" + rs.getString("reason") +
                                (rs.getBoolean("active") ? " §a(Active)" : " §7(Pardoned)");
                        history.put(ts, entry);
                    }
                }
            }

            // Pardons
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT p.timestamp, p.reason, p.staff_uuid, b.uuid " +
                    "FROM pardons p JOIN bans b ON p.ban_id = b.id WHERE b.uuid = ?")) {
                for (UUID uuid : alts) {
                    stmt.setString(1, uuid.toString());
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        long ts = rs.getLong("timestamp");
                        String entry = "§bPardon §7[" + new Timestamp(ts) + "] on §f" +
                                altManager.getPlayerName(UUID.fromString(rs.getString("uuid"))) +
                                " §7by §f" + altManager.getPlayerName(UUID.fromString(rs.getString("staff_uuid"))) +
                                "§7: §f" + rs.getString("reason");
                        history.put(ts, entry);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to generate ban report: " + e.getMessage());
        }

        history.values().forEach(report::add);
        return report;
    }
}
