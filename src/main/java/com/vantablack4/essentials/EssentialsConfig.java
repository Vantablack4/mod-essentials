package com.vantablack4.essentials;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

public record EssentialsConfig(
    Path configDirectory,
    int adminPermissionLevel,
    int maxHomesPerPlayer,
    int teleportRequestTimeoutSeconds,
    boolean removeEffectsOnHeal,
    boolean runtimeFlyAndGod
) {
    private static final int DEFAULT_ADMIN_PERMISSION_LEVEL = 2;
    private static final int DEFAULT_MAX_HOMES = 5;
    private static final int DEFAULT_TPA_TIMEOUT_SECONDS = 120;

    public static EssentialsConfig load() {
        Path configDirectory = FabricLoader.getInstance().getConfigDir().resolve(VantablackEssentialsMod.MOD_ID);
        Path configFile = configDirectory.resolve("config.properties");
        Properties properties = new Properties();

        try {
            Files.createDirectories(configDirectory);
            writeDefaultYamlTemplate(configDirectory.resolve("config.yml"));
            if (Files.isRegularFile(configFile)) {
                try (Reader reader = Files.newBufferedReader(configFile)) {
                    properties.load(reader);
                }
            } else {
                writeDefaultConfig(configFile);
            }
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to read Vantablack Essentials config, using defaults", exception);
        }

        return new EssentialsConfig(
            configDirectory,
            boundedInt(properties, "commands.admin-permission-level", DEFAULT_ADMIN_PERMISSION_LEVEL, 0, 4),
            boundedInt(properties, "homes.max-per-player", DEFAULT_MAX_HOMES, 1, 100),
            boundedInt(properties, "teleport.request-timeout-seconds", DEFAULT_TPA_TIMEOUT_SECONDS, 5, 600),
            bool(properties, "healing.remove-effects-on-heal", true),
            bool(properties, "runtime.fly-and-god-enabled", true)
        );
    }

    private static void writeDefaultConfig(Path configFile) throws IOException {
        Properties defaults = new Properties();
        defaults.setProperty("commands.admin-permission-level", Integer.toString(DEFAULT_ADMIN_PERMISSION_LEVEL));
        defaults.setProperty("homes.max-per-player", Integer.toString(DEFAULT_MAX_HOMES));
        defaults.setProperty("teleport.request-timeout-seconds", Integer.toString(DEFAULT_TPA_TIMEOUT_SECONDS));
        defaults.setProperty("healing.remove-effects-on-heal", "true");
        defaults.setProperty("runtime.fly-and-god-enabled", "true");
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            defaults.store(writer, "Vantablack Essentials configuration");
        }
    }

    private static void writeDefaultYamlTemplate(Path configFile) throws IOException {
        if (Files.isRegularFile(configFile)) {
            return;
        }
        try (InputStream stream = EssentialsConfig.class.getClassLoader().getResourceAsStream("essentialsx/config.yml")) {
            if (stream == null) {
                return;
            }
            Files.copy(stream, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> fallback;
        };
    }

    private static int boundedInt(Properties properties, String key, int fallback, int min, int max) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
