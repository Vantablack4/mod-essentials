package com.vantablack4.essentials;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

public final class EssentialsStorage {
    private static final String HOME_PREFIX = "home.";
    private static final String WARP_PREFIX = "warp.";

    private final Path configDirectory;
    private final Path spawnFile;
    private final Path homesFile;
    private final Path warpsFile;
    private final Properties spawnProperties;
    private final Properties homeProperties;
    private final Properties warpProperties;

    private EssentialsStorage(
        Path configDirectory,
        Properties spawnProperties,
        Properties homeProperties,
        Properties warpProperties
    ) {
        this.configDirectory = configDirectory;
        this.spawnFile = configDirectory.resolve("spawn.properties");
        this.homesFile = configDirectory.resolve("homes.properties");
        this.warpsFile = configDirectory.resolve("warps.properties");
        this.spawnProperties = spawnProperties;
        this.homeProperties = homeProperties;
        this.warpProperties = warpProperties;
    }

    public static EssentialsStorage load(Path configDirectory) {
        try {
            Files.createDirectories(configDirectory);
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to create Vantablack Essentials config directory", exception);
        }
        return new EssentialsStorage(
            configDirectory,
            loadProperties(configDirectory.resolve("spawn.properties")),
            loadProperties(configDirectory.resolve("homes.properties")),
            loadProperties(configDirectory.resolve("warps.properties"))
        );
    }

    public synchronized Optional<StoredLocation> spawn() {
        return spawn("default");
    }

    public synchronized Optional<StoredLocation> spawn(String group) {
        return StoredLocation.read(spawnProperties, spawnPrefix(group));
    }

    public synchronized void setSpawn(StoredLocation location) {
        setSpawn("default", location);
    }

    public synchronized void setSpawn(String group, StoredLocation location) {
        StoredLocation.write(spawnProperties, spawnPrefix(group), location);
        saveProperties(spawnFile, spawnProperties, "Vantablack Essentials spawn");
    }

    public synchronized List<String> homes(UUID playerUuid) {
        String prefix = HOME_PREFIX + playerUuid + ".";
        return homeProperties.stringPropertyNames().stream()
            .filter(key -> key.startsWith(prefix) && key.endsWith(".name"))
            .map(key -> homeProperties.getProperty(key))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public synchronized Optional<StoredLocation> home(UUID playerUuid, String name) {
        return StoredLocation.read(homeProperties, homePrefix(playerUuid, name));
    }

    public synchronized void setHome(UUID playerUuid, String name, StoredLocation location) {
        String prefix = homePrefix(playerUuid, name);
        homeProperties.setProperty(prefix + ".name", normalizeName(name));
        StoredLocation.write(homeProperties, prefix, location);
        saveProperties(homesFile, homeProperties, "Vantablack Essentials homes");
    }

    public synchronized boolean deleteHome(UUID playerUuid, String name) {
        String prefix = homePrefix(playerUuid, name) + ".";
        boolean removed = removePrefixed(homeProperties, prefix);
        if (removed) {
            saveProperties(homesFile, homeProperties, "Vantablack Essentials homes");
        }
        return removed;
    }

    public synchronized boolean renameHome(UUID playerUuid, String oldName, String newName) {
        String oldNormalized = normalizeName(oldName);
        String newNormalized = normalizeName(newName);
        if (oldNormalized.isBlank() || newNormalized.isBlank() || oldNormalized.equals(newNormalized)) {
            return false;
        }
        Optional<StoredLocation> location = home(playerUuid, oldNormalized);
        if (location.isEmpty()) {
            return false;
        }
        deleteHome(playerUuid, oldNormalized);
        setHome(playerUuid, newNormalized, location.get());
        return true;
    }

    public synchronized List<String> warps() {
        List<String> names = new ArrayList<>();
        for (String key : warpProperties.stringPropertyNames()) {
            if (key.startsWith(WARP_PREFIX) && key.endsWith(".name")) {
                names.add(warpProperties.getProperty(key));
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public synchronized Optional<StoredLocation> warp(String name) {
        return StoredLocation.read(warpProperties, warpPrefix(name));
    }

    public synchronized void setWarp(String name, StoredLocation location) {
        String prefix = warpPrefix(name);
        warpProperties.setProperty(prefix + ".name", normalizeName(name));
        StoredLocation.write(warpProperties, prefix, location);
        saveProperties(warpsFile, warpProperties, "Vantablack Essentials warps");
    }

    public synchronized boolean deleteWarp(String name) {
        String prefix = warpPrefix(name) + ".";
        boolean removed = removePrefixed(warpProperties, prefix);
        if (removed) {
            saveProperties(warpsFile, warpProperties, "Vantablack Essentials warps");
        }
        return removed;
    }

    public static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        while (normalized.contains("__")) {
            normalized = normalized.replace("__", "_");
        }
        return normalized.replaceAll("^_+|_+$", "");
    }

    private String homePrefix(UUID playerUuid, String name) {
        return HOME_PREFIX + playerUuid + "." + normalizeName(name);
    }

    private String warpPrefix(String name) {
        return WARP_PREFIX + normalizeName(name);
    }

    private static String spawnPrefix(String group) {
        String normalized = normalizeName(group);
        return "spawn." + (normalized.isBlank() ? "default" : normalized);
    }

    private static boolean removePrefixed(Properties properties, String prefix) {
        List<String> keys = properties.stringPropertyNames().stream()
            .filter(key -> key.startsWith(prefix))
            .toList();
        keys.forEach(properties::remove);
        return !keys.isEmpty();
    }

    private static Properties loadProperties(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to load Vantablack Essentials state file: {}", file, exception);
        }
        return properties;
    }

    private static void saveProperties(Path file, Properties properties, String comment) {
        try {
            Files.createDirectories(file.getParent());
            Path temporaryFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, comment);
            }
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save Vantablack Essentials state file: " + file, exception);
        }
    }
}
