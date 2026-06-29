package com.vantablack4.essentials.commands.economy;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;

import com.vantablack4.essentials.VantablackEssentialsMod;

record EconomySettings(
    BigDecimal startingBalance,
    BigDecimal minMoney,
    BigDecimal maxMoney,
    BigDecimal minimumPayAmount,
    String currencySymbol,
    boolean currencySymbolSuffix,
    boolean showZeroBalanceTop,
    boolean allowSellingNamedItems,
    int balanceTopPageSize,
    Duration paymentConfirmationTimeout
) {
    private static final BigDecimal DEFAULT_STARTING_BALANCE = BigDecimal.ZERO;
    private static final BigDecimal DEFAULT_MIN_MONEY = new BigDecimal("-10000");
    private static final BigDecimal DEFAULT_MAX_MONEY = new BigDecimal("10000000000000");
    private static final BigDecimal DEFAULT_MINIMUM_PAY_AMOUNT = new BigDecimal("0.001");
    private static final int DEFAULT_BALANCE_TOP_PAGE_SIZE = 10;
    private static final int DEFAULT_CONFIRMATION_SECONDS = 30;

    static EconomySettings load(Path configDirectory) {
        Path propertiesFile = configDirectory.resolve("economy.properties");
        Properties properties = new Properties();
        if (Files.isRegularFile(propertiesFile)) {
            try (Reader reader = Files.newBufferedReader(propertiesFile, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException exception) {
                VantablackEssentialsMod.LOGGER.warn("Unable to read economy.properties, using defaults", exception);
            }
        }

        Properties yamlValues = readFlatYaml(configDirectory.resolve("config.yml"));
        putIfAbsent(properties, "starting-balance", yamlValues.getProperty("starting-balance"), DEFAULT_STARTING_BALANCE.toPlainString());
        putIfAbsent(properties, "min-money", yamlValues.getProperty("min-money"), DEFAULT_MIN_MONEY.toPlainString());
        putIfAbsent(properties, "max-money", yamlValues.getProperty("max-money"), DEFAULT_MAX_MONEY.toPlainString());
        putIfAbsent(properties, "minimum-pay-amount", yamlValues.getProperty("minimum-pay-amount"), DEFAULT_MINIMUM_PAY_AMOUNT.toPlainString());
        putIfAbsent(properties, "currency-symbol", yamlValues.getProperty("currency-symbol"), "$");
        putIfAbsent(properties, "currency-symbol-suffix", yamlValues.getProperty("currency-symbol-suffix"), "false");
        putIfAbsent(properties, "show-zero-baltop", yamlValues.getProperty("show-zero-baltop"), "true");
        putIfAbsent(properties, "allow-selling-named-items", yamlValues.getProperty("allow-selling-named-items"), "false");
        putIfAbsent(properties, "balancetop-page-size", null, Integer.toString(DEFAULT_BALANCE_TOP_PAGE_SIZE));
        putIfAbsent(properties, "pay-confirm-timeout-seconds", null, Integer.toString(DEFAULT_CONFIRMATION_SECONDS));
        saveProperties(propertiesFile, properties);

        return new EconomySettings(
            positiveOrZero(properties.getProperty("starting-balance"), DEFAULT_STARTING_BALANCE),
            decimal(properties.getProperty("min-money"), DEFAULT_MIN_MONEY),
            decimal(properties.getProperty("max-money"), DEFAULT_MAX_MONEY),
            positiveOrZero(properties.getProperty("minimum-pay-amount"), DEFAULT_MINIMUM_PAY_AMOUNT),
            properties.getProperty("currency-symbol", "$"),
            bool(properties.getProperty("currency-symbol-suffix"), false),
            bool(properties.getProperty("show-zero-baltop"), true),
            bool(properties.getProperty("allow-selling-named-items"), false),
            boundedInt(properties.getProperty("balancetop-page-size"), DEFAULT_BALANCE_TOP_PAGE_SIZE, 1, 100),
            Duration.ofSeconds(boundedInt(properties.getProperty("pay-confirm-timeout-seconds"), DEFAULT_CONFIRMATION_SECONDS, 5, 600))
        );
    }

    BigDecimal clamp(BigDecimal amount) {
        if (amount.compareTo(minMoney) < 0) {
            return minMoney;
        }
        if (amount.compareTo(maxMoney) > 0) {
            return maxMoney;
        }
        return amount;
    }

    boolean wouldExceedMax(BigDecimal amount) {
        return amount.compareTo(maxMoney) > 0;
    }

    boolean wouldFallBelowMin(BigDecimal amount) {
        return amount.compareTo(minMoney) < 0;
    }

    private static void putIfAbsent(Properties properties, String key, String preferredValue, String fallback) {
        if (properties.containsKey(key)) {
            return;
        }
        String value = preferredValue == null || preferredValue.isBlank() ? fallback : preferredValue;
        properties.setProperty(key, value);
    }

    private static Properties readFlatYaml(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try {
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) {
                    continue;
                }
                int separator = line.indexOf(':');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = stripYamlValue(line.substring(separator + 1));
                if (!key.isEmpty() && !value.isEmpty()) {
                    properties.setProperty(key, value);
                }
            }
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to read EssentialsX config.yml economy settings", exception);
        }
        return properties;
    }

    private static String stripYamlValue(String rawValue) {
        String value = rawValue.trim();
        int comment = value.indexOf(" #");
        if (comment >= 0) {
            value = value.substring(0, comment).trim();
        }
        if (value.length() >= 2 && ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\"")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static BigDecimal positiveOrZero(String rawValue, BigDecimal fallback) {
        BigDecimal value = decimal(rawValue, fallback);
        return value.signum() < 0 ? fallback : value;
    }

    private static BigDecimal decimal(String rawValue, BigDecimal fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(rawValue.trim().replace(",", ""));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean bool(String rawValue, boolean fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        return switch (rawValue.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1", "on", "enabled" -> true;
            case "false", "no", "0", "off", "disabled" -> false;
            default -> fallback;
        };
    }

    private static int boundedInt(String rawValue, int fallback, int min, int max) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static void saveProperties(Path file, Properties properties) {
        try {
            Files.createDirectories(file.getParent());
            Path temporaryFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "Vantablack Essentials local economy settings");
            }
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save economy settings: " + file, exception);
        }
    }
}
