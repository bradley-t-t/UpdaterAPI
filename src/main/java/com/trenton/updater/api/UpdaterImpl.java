package com.trenton.updater.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class UpdaterImpl implements UpdaterService {
    private final JavaPlugin plugin;
    private final int resourceId;
    private String latestVersion;
    private boolean updateAvailable;
    private File downloadedUpdate;

    public UpdaterImpl(JavaPlugin plugin, int resourceId) {
        this.plugin = plugin;
        this.resourceId = resourceId;
        this.updateAvailable = false;
    }

    @Override
    public void checkForUpdates(boolean autoUpdate) {
        try {
            URL url = new URL("https://api.spiget.org/v2/resources/" + resourceId + "/versions/latest");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", plugin.getName() + "-Updater");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                plugin.getLogger().warning("Failed to check for updates: HTTP " + responseCode);
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            connection.disconnect();

            JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
            if (!json.has("name")) {
                plugin.getLogger().warning("Spiget API response missing 'name' field");
                return;
            }
            latestVersion = json.get("name").getAsString().trim();
            String currentVersion = plugin.getDescription().getVersion().trim();

            if (isVersionNewer(latestVersion, currentVersion)) {
                updateAvailable = true;
                plugin.getLogger().info("Update available: v" + latestVersion + " (current: v" + currentVersion + ")");
                if (autoUpdate) {
                    downloadUpdate();
                }
            } else {
                plugin.getLogger().info("No update available. Current: v" + currentVersion + ", Spigot: v" + latestVersion);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to check for updates: " + e.getMessage());
        }
    }

    private boolean isVersionNewer(String latest, String current) {
        try {
            String normalizedLatest = latest.replaceAll("[^0-9.]", "").trim();
            String normalizedCurrent = current.replaceAll("[^0-9.]", "").trim();
            String[] latestParts = normalizedLatest.split("\\.");
            String[] currentParts = normalizedCurrent.split("\\.");
            int maxLength = Math.max(latestParts.length, currentParts.length);
            for (int i = 0; i < maxLength; i++) {
                int latestNum = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
                int currentNum = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                if (latestNum > currentNum) return true;
                if (latestNum < currentNum) return false;
            }
            return false;
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("Invalid version format: latest=" + latest + ", current=" + current);
            return false;
        }
    }

    private void downloadUpdate() {
        try {
            File updateFolder = new File(plugin.getDataFolder(), "AutoUpdater");
            if (!updateFolder.exists()) {
                updateFolder.mkdirs();
            }
            downloadedUpdate = new File(updateFolder, plugin.getName() + "-" + latestVersion + ".jar");
            if (downloadedUpdate.exists()) {
                plugin.getLogger().info("Update file " + downloadedUpdate.getPath() + " already exists.");
                return;
            }

            URL url = new URL("https://api.spiget.org/v2/resources/" + resourceId + "/download");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", plugin.getName() + "-Updater");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                plugin.getLogger().warning("Failed to download update: HTTP " + responseCode);
                return;
            }

            try (var inputStream = connection.getInputStream();
                 var readableByteChannel = Channels.newChannel(inputStream);
                 var fileOutputStream = new FileOutputStream(downloadedUpdate)) {
                fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
                plugin.getLogger().info("Downloaded update to " + downloadedUpdate.getPath() + ".");
            }
            connection.disconnect();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to download update: " + e.getMessage());
            downloadedUpdate = null;
        }
    }

    @Override
    public void handleUpdateOnShutdown() {
        if (downloadedUpdate == null || !downloadedUpdate.exists()) {
            return;
        }
        try {
            File pluginsFolder = new File("plugins");
            File currentJar = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            File[] existingJars = pluginsFolder.listFiles((dir, name) -> name.startsWith(plugin.getName()) && name.endsWith(".jar"));
            if (existingJars != null) {
                for (File jar : existingJars) {
                    try {
                        Files.deleteIfExists(jar.toPath());
                        plugin.getLogger().info("Deleted old JAR: " + jar.getPath());
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to delete old JAR " + jar.getPath() + ": " + e.getMessage());
                    }
                }
            }
            if (currentJar.exists() && currentJar.isFile()) {
                try {
                    Files.deleteIfExists(currentJar.toPath());
                    plugin.getLogger().info("Deleted current JAR: " + currentJar.getPath());
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to delete current JAR " + currentJar.getPath() + ": " + e.getMessage());
                }
            }
            File targetFile = new File(pluginsFolder, plugin.getName() + "-" + latestVersion + ".jar");
            Files.move(downloadedUpdate.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Moved update to " + targetFile.getPath() + ". Restart server to apply.");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to apply update: " + e.getMessage());
            plugin.getLogger().info("To apply update manually: 1) Stop server. 2) Remove old " + plugin.getName() + " JARs. 3) Move " + downloadedUpdate.getPath() + " to plugins/" + plugin.getName() + "-" + latestVersion + ".jar. 4) Restart server.");
        }
    }

    @Override
    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    @Override
    public String getLatestVersion() {
        return latestVersion;
    }
}