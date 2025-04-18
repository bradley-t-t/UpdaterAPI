package com.trenton.updater.api;

public interface UpdaterService {
    void checkForUpdates(boolean autoUpdate);

    boolean isUpdateAvailable();

    String getLatestVersion();

    void handleUpdateOnShutdown();
}