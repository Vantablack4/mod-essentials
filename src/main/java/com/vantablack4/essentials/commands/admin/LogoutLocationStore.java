package com.vantablack4.essentials.commands.admin;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import com.vantablack4.essentials.StoredLocation;
import com.vantablack4.essentials.VantablackEssentialsMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

final class LogoutLocationStore {
    private static final String PREFIX = "logout.";

    private final Path file;
    private final Properties properties;

    LogoutLocationStore(Path file) {
        this.file = file;
        this.properties = load(file);
    }

    synchronized void rememberIdentity(ServerPlayer player) {
        writeIdentity(player);
        save();
    }

    synchronized void record(ServerPlayer player) {
        String prefix = prefix(player.getUUID());
        writeIdentity(player);
        writeLocation(prefix, StoredLocation.capture(player));
        properties.setProperty(prefix + ".logout-at", Long.toString(System.currentTimeMillis()));
        save();
    }

    synchronized Optional<LogoutRecord> get(UUID uuid) {
        return read(uuid);
    }

    synchronized Optional<LogoutRecord> find(String rawTarget) {
        String target = cleanInput(rawTarget);
        if (target.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<LogoutRecord> byUuid = get(UUID.fromString(target));
            if (byUuid.isPresent()) {
                return byUuid;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to name matching.
        }
        String folded = target.toLowerCase(Locale.ROOT);
        return records().stream()
            .filter(record -> record.name().equalsIgnoreCase(target) || record.displayName().toLowerCase(Locale.ROOT).equals(folded))
            .max(Comparator.comparingLong(LogoutRecord::logoutAtEpochMillis));
    }

    synchronized Optional<String> ipAddressFor(String rawTarget) {
        String target = cleanInput(rawTarget);
        if (target.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<String> byUuid = ipAddress(UUID.fromString(target));
            if (byUuid.isPresent()) {
                return byUuid;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to name matching.
        }
        String folded = target.toLowerCase(Locale.ROOT);
        return identities().stream()
            .filter(identity -> identity.name().equalsIgnoreCase(target) || identity.displayName().toLowerCase(Locale.ROOT).equals(folded))
            .max(Comparator.comparingLong(Identity::updatedAtEpochMillis))
            .flatMap(Identity::ipAddress);
    }

    synchronized Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        for (Identity identity : identities()) {
            names.add(identity.name());
            if (!identity.displayName().equalsIgnoreCase(identity.name())) {
                names.add(identity.displayName());
            }
        }
        return names;
    }

    synchronized Set<String> ipAddresses() {
        Set<String> ipAddresses = new LinkedHashSet<>();
        for (Identity identity : identities()) {
            identity.ipAddress().ifPresent(ipAddresses::add);
        }
        return ipAddresses;
    }

    private void writeIdentity(ServerPlayer player) {
        String prefix = prefix(player.getUUID());
        properties.setProperty(prefix + ".uuid", player.getUUID().toString());
        properties.setProperty(prefix + ".name", player.getScoreboardName());
        properties.setProperty(prefix + ".display-name", player.getDisplayName().getString());
        String ipAddress = player.getIpAddress();
        if (ipAddress != null && !ipAddress.isBlank()) {
            properties.setProperty(prefix + ".ip-address", ipAddress);
        }
        properties.setProperty(prefix + ".updated-at", Long.toString(System.currentTimeMillis()));
    }

    private Optional<String> ipAddress(UUID uuid) {
        String value = properties.getProperty(prefix(uuid) + ".ip-address");
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private List<LogoutRecord> records() {
        List<LogoutRecord> records = new ArrayList<>();
        for (Identity identity : identities()) {
            read(identity.uuid()).ifPresent(records::add);
        }
        return records;
    }

    private List<Identity> identities() {
        List<Identity> identities = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(PREFIX) && key.endsWith(".uuid")) {
                try {
                    readIdentity(UUID.fromString(properties.getProperty(key))).ifPresent(identities::add);
                } catch (IllegalArgumentException ignored) {
                    // Ignore corrupt rows.
                }
            }
        }
        identities.sort(Comparator.comparing(Identity::name, String.CASE_INSENSITIVE_ORDER));
        return identities;
    }

    private Optional<Identity> readIdentity(UUID uuid) {
        String prefix = prefix(uuid);
        String name = properties.getProperty(prefix + ".name");
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String displayName = properties.getProperty(prefix + ".display-name", name);
        try {
            return Optional.of(new Identity(
                uuid,
                name,
                displayName,
                ipAddress(uuid),
                Long.parseLong(properties.getProperty(prefix + ".updated-at", "0"))
            ));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private Optional<LogoutRecord> read(UUID uuid) {
        Optional<Identity> identity = readIdentity(uuid);
        Optional<StoredLocation> location = readLocation(prefix(uuid));
        if (identity.isEmpty() || location.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new LogoutRecord(
                uuid,
                identity.get().name(),
                identity.get().displayName(),
                identity.get().ipAddress(),
                Long.parseLong(properties.getProperty(prefix(uuid) + ".logout-at", "0")),
                location.get()
            ));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
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

    private String prefix(UUID uuid) {
        return PREFIX + uuid + ".";
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporaryFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "Vantablack Essentials logout locations");
            }
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save logout locations: " + file, exception);
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
            VantablackEssentialsMod.LOGGER.warn("Unable to load logout locations: {}", file, exception);
        }
        return properties;
    }

    private static String cleanInput(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").trim();
        }
        return trimmed;
    }

    record LogoutRecord(UUID uuid, String name, String displayName, Optional<String> ipAddress, long logoutAtEpochMillis, StoredLocation location) {
    }

    private record Identity(UUID uuid, String name, String displayName, Optional<String> ipAddress, long updatedAtEpochMillis) {
    }
}
