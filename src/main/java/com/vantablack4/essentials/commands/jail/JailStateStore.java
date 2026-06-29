package com.vantablack4.essentials.commands.jail;

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

import com.vantablack4.essentials.StoredLocation;
import com.vantablack4.essentials.VantablackEssentialsMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

final class JailStateStore {
    private static final String JAIL_PREFIX = "jail.";
    private static final String PLAYER_PREFIX = "player.";

    private final Path file;
    private final Properties properties;

    JailStateStore(Path file) {
        this.file = file;
        this.properties = load(file);
    }

    synchronized List<String> jails() {
        List<String> names = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(JAIL_PREFIX) && key.endsWith(".name")) {
                names.add(properties.getProperty(key));
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    synchronized Optional<StoredLocation> jail(String name) {
        return readLocation(jailPrefix(name));
    }

    synchronized void setJail(String name, StoredLocation location) {
        String prefix = jailPrefix(name);
        properties.setProperty(prefix + ".name", normalize(name));
        writeLocation(prefix, location);
        save();
    }

    synchronized boolean deleteJail(String name) {
        return removePrefix(jailPrefix(name) + ".");
    }

    synchronized Optional<JailedPlayer> jailed(UUID uuid) {
        String prefix = playerPrefix(uuid);
        String jail = properties.getProperty(prefix + ".jail");
        String name = properties.getProperty(prefix + ".name");
        if (jail == null || jail.isBlank() || name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new JailedPlayer(
                uuid,
                name,
                jail,
                properties.getProperty(prefix + ".source", "Console"),
                properties.getProperty(prefix + ".reason", "Jailed"),
                Long.parseLong(properties.getProperty(prefix + ".created", "0"))
            ));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    synchronized List<JailedPlayer> jailedPlayers() {
        List<JailedPlayer> players = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(PLAYER_PREFIX) && key.endsWith(".uuid")) {
                try {
                    jailed(UUID.fromString(properties.getProperty(key))).ifPresent(players::add);
                } catch (IllegalArgumentException ignored) {
                    // Ignore corrupt rows.
                }
            }
        }
        players.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        return players;
    }

    synchronized void jail(UUID uuid, String name, String jail, String source, String reason) {
        String prefix = playerPrefix(uuid);
        properties.setProperty(prefix + ".uuid", uuid.toString());
        properties.setProperty(prefix + ".name", name);
        properties.setProperty(prefix + ".jail", normalize(jail));
        properties.setProperty(prefix + ".source", source);
        properties.setProperty(prefix + ".reason", reason == null || reason.isBlank() ? "Jailed" : reason);
        properties.setProperty(prefix + ".created", Long.toString(System.currentTimeMillis()));
        save();
    }

    synchronized boolean release(UUID uuid) {
        return removePrefix(playerPrefix(uuid));
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        while (normalized.contains("__")) {
            normalized = normalized.replace("__", "_");
        }
        return normalized.replaceAll("^_+|_+$", "");
    }

    private String jailPrefix(String name) {
        return JAIL_PREFIX + normalize(name);
    }

    private String playerPrefix(UUID uuid) {
        return PLAYER_PREFIX + uuid + ".";
    }

    private boolean removePrefix(String prefix) {
        List<String> keys = properties.stringPropertyNames().stream()
            .filter(key -> key.startsWith(prefix))
            .toList();
        keys.forEach(properties::remove);
        if (!keys.isEmpty()) {
            save();
        }
        return !keys.isEmpty();
    }

    private void writeLocation(String prefix, StoredLocation location) {
        properties.setProperty(prefix + ".dimension", location.dimension().identifier().toString());
        properties.setProperty(prefix + ".x", Double.toString(location.x()));
        properties.setProperty(prefix + ".y", Double.toString(location.y()));
        properties.setProperty(prefix + ".z", Double.toString(location.z()));
        properties.setProperty(prefix + ".yaw", Float.toString(location.yaw()));
        properties.setProperty(prefix + ".pitch", Float.toString(location.pitch()));
    }

    private Optional<StoredLocation> readLocation(String prefix) {
        Identifier dimensionId = Identifier.tryParse(properties.getProperty(prefix + ".dimension", ""));
        if (dimensionId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StoredLocation(
                ResourceKey.create(Registries.DIMENSION, dimensionId),
                Double.parseDouble(properties.getProperty(prefix + ".x")),
                Double.parseDouble(properties.getProperty(prefix + ".y")),
                Double.parseDouble(properties.getProperty(prefix + ".z")),
                Float.parseFloat(properties.getProperty(prefix + ".yaw", "0")),
                Float.parseFloat(properties.getProperty(prefix + ".pitch", "0"))
            ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporaryFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "Vantablack Essentials jails");
            }
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save jail state: " + file, exception);
        }
    }

    private static Properties load(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to load jail state: {}", file, exception);
        }
        return properties;
    }

    record JailedPlayer(UUID uuid, String name, String jail, String source, String reason, long createdAtEpochMillis) {
    }
}
