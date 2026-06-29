package com.vantablack4.essentials;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;

public final class TeleportStateService {
    private static final String PREFIX = "state.";

    private final Map<UUID, TeleportState> states = new ConcurrentHashMap<>();
    private final Path file;
    private final Properties properties;

    public TeleportStateService() {
        this(null);
    }

    public TeleportStateService(Path file) {
        this.file = file;
        this.properties = file == null ? new Properties() : loadProperties(file);
        loadStates();
    }

    public boolean isTeleportEnabled(ServerPlayer player) {
        return state(player).teleportEnabled();
    }

    public ToggleResult setTeleportEnabled(ServerPlayer player, boolean enabled) {
        TeleportState updated = states.compute(player.getUUID(), (uuid, state) -> {
            TeleportState previous = state == null ? TeleportState.DEFAULT : state;
            return previous.withTeleportEnabled(enabled);
        });
        saveState(player.getUUID(), updated);
        return new ToggleResult(enabled);
    }

    public ToggleResult toggleTeleportEnabled(ServerPlayer player) {
        TeleportState updated = states.compute(player.getUUID(), (uuid, state) -> {
            TeleportState previous = state == null ? TeleportState.DEFAULT : state;
            return previous.withTeleportEnabled(!previous.teleportEnabled());
        });
        saveState(player.getUUID(), updated);
        return new ToggleResult(updated.teleportEnabled());
    }

    public boolean isAutoTeleportEnabled(ServerPlayer player) {
        return state(player).autoTeleportEnabled();
    }

    public ToggleResult setAutoTeleportEnabled(ServerPlayer player, boolean enabled) {
        TeleportState updated = states.compute(player.getUUID(), (uuid, state) -> {
            TeleportState previous = state == null ? TeleportState.DEFAULT : state;
            return previous.withAutoTeleportEnabled(enabled);
        });
        saveState(player.getUUID(), updated);
        return new ToggleResult(enabled);
    }

    public ToggleResult toggleAutoTeleportEnabled(ServerPlayer player) {
        TeleportState updated = states.compute(player.getUUID(), (uuid, state) -> {
            TeleportState previous = state == null ? TeleportState.DEFAULT : state;
            return previous.withAutoTeleportEnabled(!previous.autoTeleportEnabled());
        });
        saveState(player.getUUID(), updated);
        return new ToggleResult(updated.autoTeleportEnabled());
    }

    private TeleportState state(ServerPlayer player) {
        return states.getOrDefault(player.getUUID(), TeleportState.DEFAULT);
    }

    private void loadStates() {
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(PREFIX) || !key.endsWith(".teleport-enabled")) {
                continue;
            }
            String rawUuid = key.substring(PREFIX.length(), key.length() - ".teleport-enabled".length());
            try {
                UUID uuid = UUID.fromString(rawUuid);
                readState(uuid).ifPresent(state -> states.put(uuid, state));
            } catch (IllegalArgumentException ignored) {
                // Ignore corrupt rows.
            }
        }
    }

    private Optional<TeleportState> readState(UUID uuid) {
        String prefix = prefix(uuid);
        String teleportEnabled = properties.getProperty(prefix + ".teleport-enabled");
        String autoTeleportEnabled = properties.getProperty(prefix + ".auto-teleport-enabled");
        if (teleportEnabled == null && autoTeleportEnabled == null) {
            return Optional.empty();
        }
        return Optional.of(new TeleportState(
            parseBoolean(teleportEnabled, TeleportState.DEFAULT.teleportEnabled()),
            parseBoolean(autoTeleportEnabled, TeleportState.DEFAULT.autoTeleportEnabled())
        ));
    }

    private synchronized void saveState(UUID uuid, TeleportState state) {
        if (file == null) {
            return;
        }
        String prefix = prefix(uuid);
        properties.setProperty(prefix + ".teleport-enabled", Boolean.toString(state.teleportEnabled()));
        properties.setProperty(prefix + ".auto-teleport-enabled", Boolean.toString(state.autoTeleportEnabled()));
        saveProperties(file, properties);
    }

    private static String prefix(UUID uuid) {
        return PREFIX + uuid;
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "yes", "1", "on", "enabled" -> true;
            case "false", "no", "0", "off", "disabled" -> false;
            default -> fallback;
        };
    }

    private static Properties loadProperties(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to load teleport state file: {}", file, exception);
        }
        return properties;
    }

    private static void saveProperties(Path file, Properties properties) {
        try {
            Files.createDirectories(file.getParent());
            Path temporaryFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "Vantablack Essentials teleport state");
            }
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save teleport state file: " + file, exception);
        }
    }

    public record ToggleResult(boolean enabled) {
    }

    private record TeleportState(boolean teleportEnabled, boolean autoTeleportEnabled) {
        private static final TeleportState DEFAULT = new TeleportState(true, false);

        private TeleportState withTeleportEnabled(boolean enabled) {
            return new TeleportState(enabled, autoTeleportEnabled);
        }

        private TeleportState withAutoTeleportEnabled(boolean enabled) {
            return new TeleportState(teleportEnabled, enabled);
        }
    }
}
