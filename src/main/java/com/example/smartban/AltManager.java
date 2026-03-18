package com.example.smartban;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class AltManager {

    private final SmartBanPlugin plugin;
    private final Map<UUID, UUID> parentMap = new HashMap<>(); // Union-Find structure for transitive alts

    public AltManager(SmartBanPlugin plugin) {
        this.plugin = plugin;
        loadAltLinks();
    }

    private void loadAltLinks() {
        try (Connection conn = plugin.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT uuid1, uuid2 FROM alt_links")) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                UUID uuid1 = UUID.fromString(rs.getString("uuid1"));
                UUID uuid2 = UUID.fromString(rs.getString("uuid2"));
                union(uuid1, uuid2);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load alt links: " + e.getMessage());
        }
    }

    // Union-Find helpers (assuming these exist from your original code)
    private void union(UUID a, UUID b) {
        UUID rootA = find(a);
        UUID rootB = find(b);
        if (!rootA.equals(rootB)) {
            parentMap.put(rootA, rootB);
        }
    }

    private UUID find(UUID uuid) {
        UUID parent = parentMap.get(uuid);
        if (parent == null || parent.equals(uuid)) {
            return uuid;
        }
        UUID root = find(parent);
        parentMap.put(uuid, root); // path compression
        return root;
    }

    public void linkAlts(UUID uuid1, UUID uuid2) {
        union(uuid1, uuid2);

        int maxRetries = 5;
        long delayMs = 100;
        boolean success = false;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            Connection conn = null;
            try {
                conn = plugin.getConnection();
                conn.setAutoCommit(false);

                try (PreparedStatement stmt = conn.prepareStatement(
                        "MERGE INTO alt_links (uuid1, uuid2) KEY(uuid1, uuid2) VALUES (?, ?), (?, ?)")) {
                    stmt.setString(1, uuid1.toString());
                    stmt.setString(2, uuid2.toString());
                    stmt.setString(3, uuid2.toString());
                    stmt.setString(4, uuid1.toString());
                    stmt.executeUpdate();
                }

                conn.commit();
                success = true;
                break;
            } catch (SQLException e) {
                if (conn != null) {
                    try {
                        conn.rollback();
                    } catch (SQLException re) {
                        plugin.getLogger().severe("Rollback failed: " + re.getMessage());
                    }
                }
                if (e.getErrorCode() != 90031 && e.getErrorCode() != 23505) { // Not busy or duplicate key
                    plugin.getLogger().severe("Failed to save alt link: " + e.getMessage());
                    return;
                }
                plugin.getLogger().warning("DB busy/duplicate on alt link — retrying in " + delayMs + "ms...");
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                delayMs *= 2;
            } finally {
                if (conn != null) {
                    try {
                        conn.setAutoCommit(true);
                        conn.close();
                    } catch (SQLException ce) {
                        // Ignore
                    }
                }
            }
        }
        if (!success) {
            plugin.getLogger().severe("Failed to link alts after " + maxRetries + " retries");
        }
    }

    public void delinkAlts(UUID uuid1, UUID uuid2) {
        // Remove direct link (both directions)
        try (Connection conn = plugin.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM alt_links WHERE (uuid1 = ? AND uuid2 = ?) OR (uuid1 = ? AND uuid2 = ?)")) {
            stmt.setString(1, uuid1.toString());
            stmt.setString(2, uuid2.toString());
            stmt.setString(3, uuid2.toString());
            stmt.setString(4, uuid1.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to delink alts: " + e.getMessage());
        }
        // Note: Union-Find root may still link indirectly — full re-sync would require reload or more complex logic
    }

    public Set<UUID> getAllAlts(UUID uuid) {
        UUID root = find(uuid);
        Set<UUID> alts = new HashSet<>();
        for (Map.Entry<UUID, UUID> entry : parentMap.entrySet()) {
            if (find(entry.getKey()).equals(root)) {
                alts.add(entry.getKey());
            }
        }
        alts.add(root); // include self
        return alts;
    }

    public void updatePlayerName(UUID uuid, String name) {
        if (name == null || name.isEmpty()) return;

        int maxRetries = 5;
        long delayMs = 100;
        boolean success = false;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            Connection conn = null;
            try {
                conn = plugin.getConnection();
                conn.setAutoCommit(false);

                try (PreparedStatement stmt = conn.prepareStatement(
                        "MERGE INTO players (uuid, name) KEY(uuid) VALUES (?, ?)")) {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, name);
                    stmt.executeUpdate();
                }

                conn.commit();
                success = true;
                break;
            } catch (SQLException e) {
                if (conn != null) {
                    try {
                        conn.rollback();
                    } catch (SQLException re) {
                        plugin.getLogger().severe("Rollback failed: " + re.getMessage());
                    }
                }
                if (e.getErrorCode() != 90031 && e.getErrorCode() != 23505) { // Not busy/duplicate
                    plugin.getLogger().severe("Failed to update player name for " + uuid + ": " + e.getMessage());
                    return;
                }
                plugin.getLogger().warning("DB busy on update name — retrying...");
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                delayMs *= 2;
            } finally {
                if (conn != null) {
                    try {
                        conn.setAutoCommit(true);
                        conn.close();
                    } catch (SQLException ce) {
                        // Ignore
                    }
                }
            }
        }
        if (!success) {
            plugin.getLogger().severe("Failed to update player name after retries");
        }
    }

    public void addIp(UUID uuid, String ip) {
        if (ip == null || ip.isEmpty()) return;

        int maxRetries = 5;
        long delayMs = 100;
        boolean success = false;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            Connection conn = null;
            try {
                conn = plugin.getConnection();
                conn.setAutoCommit(false);

                try (PreparedStatement stmt = conn.prepareStatement(
                        "MERGE INTO ips (uuid, ip) KEY(uuid, ip) VALUES (?, ?)")) {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, ip);
                    stmt.executeUpdate();
                }

                conn.commit();
                success = true;
                break;
            } catch (SQLException e) {
                if (conn != null) {
                    try {
                        conn.rollback();
                    } catch (SQLException re) {
                        plugin.getLogger().severe("Rollback failed: " + re.getMessage());
                    }
                }
                if (e.getErrorCode() != 90031 && e.getErrorCode() != 23505) {
                    plugin.getLogger().severe("Failed to add IP " + ip + " for " + uuid + ": " + e.getMessage());
                    return;
                }
                plugin.getLogger().warning("DB busy on add IP — retrying...");
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                delayMs *= 2;
            } finally {
                if (conn != null) {
                    try {
                        conn.setAutoCommit(true);
                        conn.close();
                    } catch (SQLException ce) {
                        // Ignore
                    }
                }
            }
        }
        if (!success) {
            plugin.getLogger().severe("Failed to add IP after " + maxRetries + " retries");
        }
    }

    public void removeIp(UUID uuid, String ip) {
        try (Connection conn = plugin.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM ips WHERE uuid = ? AND ip = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, ip);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to remove IP: " + e.getMessage());
        }
    }

    public Set<String> getIps(UUID uuid) {
        Set<String> ips = new HashSet<>();
        try (Connection conn = plugin.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT ip FROM ips WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                ips.add(rs.getString("ip"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get IPs: " + e.getMessage());
        }
        return ips;
    }

    public UUID getUuidFromName(String name) {
        Player player = Bukkit.getPlayer(name);
        if (player != null) {
            return player.getUniqueId();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore()) {
            return offline.getUniqueId();
        }
        try (Connection conn = plugin.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT uuid FROM players WHERE name = ?")) {
            stmt.setString(1, name);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return UUID.fromString(rs.getString("uuid"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get UUID from name: " + e.getMessage());
        }
        return null;
    }

    public String getPlayerName(UUID uuid) {
        try (Connection conn = plugin.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT name FROM players WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("name");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get player name: " + e.getMessage());
        }
        return "Unknown";
    }
}
