package com.example.smartban;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;

public class SmartBanPlugin extends JavaPlugin implements Listener {

    private HikariDataSource dataSource;
    private FileConfiguration config;
    private BanManager banManager;
    private AltManager altManager;

    // DB write queue + worker
    private final BlockingQueue<Runnable> dbWriteQueue = new LinkedBlockingQueue<>(500);
    private Thread dbWorkerThread;
    private volatile boolean running = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();

        setupDatabase();

        altManager = new AltManager(this);
        banManager = new BanManager(this, altManager);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new LoginListener(this), this);

        registerCommands();

        startDbWriteWorker();

        getLogger().info("SmartBan enabled successfully!");
    }

    @Override
    public void onDisable() {
        running = false;
        if (dbWorkerThread != null) {
            dbWorkerThread.interrupt();
            try {
                dbWorkerThread.join(5000);
            } catch (InterruptedException ignored) {}
        }

        if (dataSource != null) {
            dataSource.close();
            getLogger().info("HikariCP connection pool closed.");
        }
        getLogger().info("SmartBan disabled.");
    }

    private void startDbWriteWorker() {
        dbWorkerThread = new Thread(() -> {
            while (running) {
                try {
                    Runnable task = dbWriteQueue.take();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    getLogger().severe("Error in DB write worker: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            getLogger().info("DB write worker stopped.");
        }, "SmartBan-DB-Writer");

        dbWorkerThread.setDaemon(true);
        dbWorkerThread.start();
        getLogger().info("Started SmartBan DB write worker thread.");
    }

    public void queueDbWrite(Runnable dbOperation) {
        if (!dbWriteQueue.offer(dbOperation)) {
            getLogger().warning("DB write queue full! Dropping operation (risk of data loss during high load).");
        }
    }

    private void setupDatabase() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File dbFile = new File(dataFolder, "bans.h2.db");

        HikariConfig hikariConfig = new HikariConfig();
        // This format works reliably with H2 2.2.224 on Linux servers
        hikariConfig.setJdbcUrl("jdbc:h2:" + dbFile.getAbsolutePath() + ";LOCK_TIMEOUT=1000;MVCC=TRUE");
        hikariConfig.setDriverClassName("com.example.smartban.h2.Driver");
        hikariConfig.setMaximumPoolSize(4);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(10000);
        hikariConfig.setValidationTimeout(3000);
        hikariConfig.setIdleTimeout(600000);
        hikariConfig.setMaxLifetime(1800000);
        hikariConfig.setLeakDetectionThreshold(60000);

        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        getLogger().info("Initializing H2 database at: " + dbFile.getAbsolutePath());
        getLogger().info("JDBC URL: " + hikariConfig.getJdbcUrl());

        try {
            dataSource = new HikariDataSource(hikariConfig);

            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(255) NOT NULL);");
                stmt.execute("CREATE TABLE IF NOT EXISTS ips (uuid VARCHAR(36), ip VARCHAR(45), PRIMARY KEY (uuid, ip));");
                stmt.execute("CREATE TABLE IF NOT EXISTS alt_links (uuid1 VARCHAR(36), uuid2 VARCHAR(36), PRIMARY KEY (uuid1, uuid2));");
                stmt.execute("CREATE TABLE IF NOT EXISTS bans (" +
                        "id IDENTITY PRIMARY KEY, " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "staff_uuid VARCHAR(36) NOT NULL, " +
                        "timestamp BIGINT NOT NULL, " +
                        "duration_hours INT NOT NULL, " +
                        "reason VARCHAR(255) NOT NULL, " +
                        "active BOOLEAN NOT NULL DEFAULT TRUE, " +
                        "action_type VARCHAR(20) DEFAULT 'BAN');");
                stmt.execute("CREATE TABLE IF NOT EXISTS pardons (" +
                        "id IDENTITY PRIMARY KEY, " +
                        "ban_id INT NOT NULL, " +
                        "staff_uuid VARCHAR(36) NOT NULL, " +
                        "timestamp BIGINT NOT NULL, " +
                        "reason VARCHAR(255) NOT NULL);");
                stmt.execute("CREATE TABLE IF NOT EXISTS ip_bans (" +
                        "ip VARCHAR(45) PRIMARY KEY, " +
                        "timestamp BIGINT NOT NULL, " +
                        "duration_hours INT NOT NULL, " +
                        "reason VARCHAR(255) NOT NULL, " +
                        "active BOOLEAN NOT NULL DEFAULT TRUE);");

                stmt.execute("ALTER TABLE bans ADD COLUMN IF NOT EXISTS action_type VARCHAR(20) DEFAULT 'BAN';");
            }

            getLogger().info("Database schema initialized successfully.");
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to setup H2 database", e);
            setEnabled(false);
        }
    }

    private void registerCommands() {
        PluginCommand banCmd = getCommand("ban");
        if (banCmd != null) banCmd.setExecutor(new BanCommand(this));

        getCommand("unban").setExecutor(new UnbanCommand(this));
        getCommand("warn").setExecutor(new WarnCommand(this));
        getCommand("banreport").setExecutor(new BanReportCommand(this));
        getCommand("altlink").setExecutor(new AltLinkCommand(this));
        getCommand("altdelink").setExecutor(new AltDelinkCommand(this));
        getCommand("altlist").setExecutor(new AltListCommand(this));
        getCommand("addip").setExecutor(new AddIpCommand(this));
        getCommand("removeip").setExecutor(new RemoveIpCommand(this));
        getCommand("ipunban").setExecutor(new IpUnbanCommand(this));
        getCommand("iplist").setExecutor(new IpListCommand(this));
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public FileConfiguration getPluginConfig() {
        return config;
    }

    public BanManager getBanManager() {
        return banManager;
    }

    public AltManager getAltManager() {
        return altManager;
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (event.getResult() == PlayerLoginEvent.Result.ALLOWED) {
            UUID uuid = event.getPlayer().getUniqueId();
            String name = event.getPlayer().getName();
            altManager.updatePlayerName(uuid, name);
        }
    }

    @Override
    public void saveDefaultConfig() {
        if (!new File(getDataFolder(), "config.yml").exists()) {
            saveResource("config.yml", false);
        } else {
            File configFile = new File(getDataFolder(), "config.yml");
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8));
            config = YamlConfiguration.loadConfiguration(configFile);
            for (String key : defConfig.getKeys(true)) {
                if (!config.contains(key)) {
                    config.set(key, defConfig.get(key));
                }
            }
            try {
                config.save(configFile);
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Could not save config.yml", e);
            }
        }
    }
}
