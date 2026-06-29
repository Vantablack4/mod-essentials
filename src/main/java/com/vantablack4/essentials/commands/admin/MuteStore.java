package com.vantablack4.essentials.commands.admin;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import com.vantablack4.essentials.VantablackEssentialsMod;

final class MuteStore {
    private static final String PREFIX = "mute.";

    private final Path file;
    private final Properties properties;

    MuteStore(Path file) {
        this.file = file;
        this.properties = load(file);
    }

    synchronized Optional<MuteRecord> get(UUID uuid) {
        Optional<MuteRecord> record = read(uuid);
        if (record.isPresent() && record.get().expired()) {
            remove(uuid);
            return Optional.empty();
        }
        return record;
    }

    synchronized List<MuteRecord> records() {
        List<MuteRecord> records = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(PREFIX) && key.endsWith(".uuid")) {
                try {
                    get(UUID.fromString(properties.getProperty(key))).ifPresent(records::add);
                } catch (IllegalArgumentException ignored) {
                    // Ignore corrupt rows.
                }
            }
        }
        records.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        return records;
    }

    synchronized void put(UUID uuid, String name, long expiresAtEpochMillis, String source, String reason) {
        String prefix = prefix(uuid);
        properties.setProperty(prefix + ".uuid", uuid.toString());
        properties.setProperty(prefix + ".name", name);
        properties.setProperty(prefix + ".created", Long.toString(System.currentTimeMillis()));
        properties.setProperty(prefix + ".expires", Long.toString(expiresAtEpochMillis));
        properties.setProperty(prefix + ".source", source);
        properties.setProperty(prefix + ".reason", reason == null || reason.isBlank() ? "Muted" : reason);
        save();
    }

    synchronized boolean remove(UUID uuid) {
        String prefix = prefix(uuid);
        List<String> keys = properties.stringPropertyNames().stream()
            .filter(key -> key.startsWith(prefix))
            .toList();
        keys.forEach(properties::remove);
        if (!keys.isEmpty()) {
            save();
        }
        return !keys.isEmpty();
    }

    private Optional<MuteRecord> read(UUID uuid) {
        String prefix = prefix(uuid);
        String name = properties.getProperty(prefix + ".name");
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new MuteRecord(
                uuid,
                name,
                Long.parseLong(properties.getProperty(prefix + ".created", "0")),
                Long.parseLong(properties.getProperty(prefix + ".expires", "-1")),
                properties.getProperty(prefix + ".source", "Console"),
                properties.getProperty(prefix + ".reason", "Muted")
            ));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private String prefix(UUID uuid) {
        return PREFIX + uuid + ".";
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporaryFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "Vantablack Essentials mutes");
            }
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save mute state: " + file, exception);
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
            VantablackEssentialsMod.LOGGER.warn("Unable to load mute state: {}", file, exception);
        }
        return properties;
    }

    record MuteRecord(UUID uuid, String name, long createdAtEpochMillis, long expiresAtEpochMillis, String source, String reason) {
        boolean permanent() {
            return expiresAtEpochMillis < 0L;
        }

        boolean expired() {
            return !permanent() && expiresAtEpochMillis <= System.currentTimeMillis();
        }

        String expiryText() {
            return permanent() ? "never" : Instant.ofEpochMilli(expiresAtEpochMillis).toString();
        }
    }
}
