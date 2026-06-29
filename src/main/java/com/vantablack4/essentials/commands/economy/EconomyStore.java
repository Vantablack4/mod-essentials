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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import com.vantablack4.essentials.VantablackEssentialsMod;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

final class EconomyStore {
    private static final String ACCOUNT_PREFIX = "account.";
    private static final String AMOUNT = "amount";
    private static final String ACCOUNT_NAME = "account-name";
    private static final String DISPLAY_NAME = "display-name";
    private static final String ACCEPTING_PAY = "accepting-pay";
    private static final String CONFIRMING_PAYMENTS = "confirming-payments";

    private final Path accountsFile;
    private final Path worthFile;
    private final Properties accounts;
    private final Properties worth;

    private EconomyStore(Path configDirectory, Properties accounts, Properties worth) {
        this.accountsFile = configDirectory.resolve("economy-accounts.properties");
        this.worthFile = configDirectory.resolve("worth.properties");
        this.accounts = accounts;
        this.worth = worth;
    }

    static EconomyStore load(Path configDirectory) {
        try {
            Files.createDirectories(configDirectory);
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to create Vantablack Essentials economy config directory", exception);
        }

        Path worthFile = configDirectory.resolve("worth.properties");
        Properties worth = loadProperties(worthFile);
        if (!Files.isRegularFile(worthFile)) {
            worth.putAll(defaultWorthValues());
            saveProperties(worthFile, worth, "Vantablack Essentials item worth values");
        }

        return new EconomyStore(
            configDirectory,
            loadProperties(configDirectory.resolve("economy-accounts.properties")),
            worth
        );
    }

    synchronized EconomyAccount touch(ServerPlayer player, BigDecimal startingBalance) {
        return touch(player.getUUID(), player.getScoreboardName(), displayName(player), startingBalance);
    }

    synchronized EconomyAccount touch(UUID uuid, String accountName, String displayName, BigDecimal startingBalance) {
        boolean changed = false;
        String prefix = accountPrefix(uuid);
        changed |= setIfDifferent(prefix + ACCOUNT_NAME, safeName(accountName, uuid.toString()));
        changed |= setIfDifferent(prefix + DISPLAY_NAME, safeName(displayName, accountName));
        if (accounts.getProperty(prefix + AMOUNT) == null) {
            accounts.setProperty(prefix + AMOUNT, writeAmount(startingBalance));
            changed = true;
        }
        if (accounts.getProperty(prefix + ACCEPTING_PAY) == null) {
            accounts.setProperty(prefix + ACCEPTING_PAY, "true");
            changed = true;
        }
        if (accounts.getProperty(prefix + CONFIRMING_PAYMENTS) == null) {
            accounts.setProperty(prefix + CONFIRMING_PAYMENTS, "false");
            changed = true;
        }
        if (changed) {
            saveAccounts();
        }
        return readAccount(uuid).orElseThrow();
    }

    synchronized Optional<EconomyAccount> account(UUID uuid) {
        return readAccount(uuid);
    }

    synchronized Optional<EconomyAccount> findAccount(String rawTarget) {
        String target = EconomyPlayerLookup.cleanInput(rawTarget);
        if (target.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<EconomyAccount> byUuid = account(UUID.fromString(target));
            if (byUuid.isPresent()) {
                return byUuid;
            }
        } catch (IllegalArgumentException ignored) {
        }

        String folded = target.toLowerCase(Locale.ROOT);
        return accounts().stream()
            .filter(account -> account.accountName().equalsIgnoreCase(target) || account.displayName().equalsIgnoreCase(target)
                || account.listedName().toLowerCase(Locale.ROOT).equals(folded))
            .findFirst();
    }

    synchronized List<EconomyAccount> accounts() {
        List<EconomyAccount> result = new ArrayList<>();
        for (UUID uuid : accountUuids()) {
            readAccount(uuid).ifPresent(result::add);
        }
        return result;
    }

    synchronized EconomyAccount setBalance(UUID uuid, BigDecimal amount) {
        accounts.setProperty(accountPrefix(uuid) + AMOUNT, writeAmount(amount));
        saveAccounts();
        return readAccount(uuid).orElseThrow();
    }

    synchronized EconomyAccount setAcceptingPay(UUID uuid, boolean enabled) {
        accounts.setProperty(accountPrefix(uuid) + ACCEPTING_PAY, Boolean.toString(enabled));
        saveAccounts();
        return readAccount(uuid).orElseThrow();
    }

    synchronized EconomyAccount setConfirmingPayments(UUID uuid, boolean enabled) {
        accounts.setProperty(accountPrefix(uuid) + CONFIRMING_PAYMENTS, Boolean.toString(enabled));
        saveAccounts();
        return readAccount(uuid).orElseThrow();
    }

    synchronized Optional<BigDecimal> worth(Identifier itemId) {
        String fullId = itemId.toString();
        String pathId = itemId.getPath();
        return decimal(worth.getProperty(fullId))
            .or(() -> decimal(worth.getProperty(pathId)))
            .filter(value -> value.signum() >= 0);
    }

    synchronized void setWorth(Identifier itemId, BigDecimal amount) {
        worth.setProperty(itemId.toString(), writeAmount(amount));
        saveProperties(worthFile, worth, "Vantablack Essentials item worth values");
    }

    synchronized List<WorthEntry> worthEntries() {
        return worth.stringPropertyNames().stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .map(key -> decimal(worth.getProperty(key)).map(value -> new WorthEntry(key, value)))
            .flatMap(Optional::stream)
            .toList();
    }

    private Optional<EconomyAccount> readAccount(UUID uuid) {
        String prefix = accountPrefix(uuid);
        String amount = accounts.getProperty(prefix + AMOUNT);
        if (amount == null) {
            return Optional.empty();
        }
        return Optional.of(new EconomyAccount(
            uuid,
            accounts.getProperty(prefix + ACCOUNT_NAME, uuid.toString()),
            accounts.getProperty(prefix + DISPLAY_NAME, accounts.getProperty(prefix + ACCOUNT_NAME, uuid.toString())),
            decimal(amount).orElse(BigDecimal.ZERO),
            bool(accounts.getProperty(prefix + ACCEPTING_PAY), true),
            bool(accounts.getProperty(prefix + CONFIRMING_PAYMENTS), false)
        ));
    }

    private Set<UUID> accountUuids() {
        Set<UUID> uuids = new LinkedHashSet<>();
        for (String key : accounts.stringPropertyNames()) {
            if (!key.startsWith(ACCOUNT_PREFIX)) {
                continue;
            }
            int uuidStart = ACCOUNT_PREFIX.length();
            int suffixStart = key.indexOf('.', uuidStart);
            if (suffixStart <= uuidStart) {
                continue;
            }
            try {
                uuids.add(UUID.fromString(key.substring(uuidStart, suffixStart)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return uuids;
    }

    private boolean setIfDifferent(String key, String value) {
        String current = accounts.getProperty(key);
        if (value.equals(current)) {
            return false;
        }
        accounts.setProperty(key, value);
        return true;
    }

    private void saveAccounts() {
        saveProperties(accountsFile, accounts, "Vantablack Essentials local economy accounts");
    }

    private static String accountPrefix(UUID uuid) {
        return ACCOUNT_PREFIX + uuid + ".";
    }

    private static String safeName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String displayName(ServerPlayer player) {
        return player.getDisplayName().getString();
    }

    private static Optional<BigDecimal> decimal(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(rawValue.trim().replace(",", "")));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static String writeAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
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

    private static Properties loadProperties(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to load Vantablack Essentials economy file: {}", file, exception);
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
            throw new IllegalStateException("Unable to save economy file: " + file, exception);
        }
    }

    private static Map<String, String> defaultWorthValues() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("minecraft:stone", "3.00");
        defaults.put("minecraft:cobblestone", "1.00");
        defaults.put("minecraft:dirt", "1.00");
        defaults.put("minecraft:grass_block", "1.00");
        defaults.put("minecraft:sand", "1.00");
        defaults.put("minecraft:gravel", "1.00");
        defaults.put("minecraft:clay_ball", "3.00");
        defaults.put("minecraft:brick", "5.00");
        defaults.put("minecraft:oak_log", "2.00");
        defaults.put("minecraft:spruce_log", "2.00");
        defaults.put("minecraft:birch_log", "2.00");
        defaults.put("minecraft:jungle_log", "2.00");
        defaults.put("minecraft:acacia_log", "2.00");
        defaults.put("minecraft:dark_oak_log", "2.00");
        defaults.put("minecraft:mangrove_log", "2.00");
        defaults.put("minecraft:cherry_log", "2.00");
        defaults.put("minecraft:bamboo_block", "2.00");
        defaults.put("minecraft:oak_planks", "0.50");
        defaults.put("minecraft:torch", "4.00");
        defaults.put("minecraft:coal", "10.00");
        defaults.put("minecraft:charcoal", "10.00");
        defaults.put("minecraft:coal_ore", "15.00");
        defaults.put("minecraft:iron_ore", "18.00");
        defaults.put("minecraft:copper_ore", "12.00");
        defaults.put("minecraft:gold_ore", "45.00");
        defaults.put("minecraft:redstone_ore", "30.00");
        defaults.put("minecraft:lapis_ore", "45.00");
        defaults.put("minecraft:diamond_ore", "200.00");
        defaults.put("minecraft:emerald_ore", "250.00");
        defaults.put("minecraft:raw_iron", "18.00");
        defaults.put("minecraft:raw_copper", "12.00");
        defaults.put("minecraft:raw_gold", "45.00");
        defaults.put("minecraft:iron_ingot", "22.00");
        defaults.put("minecraft:copper_ingot", "15.00");
        defaults.put("minecraft:gold_ingot", "50.00");
        defaults.put("minecraft:diamond", "500.00");
        defaults.put("minecraft:emerald", "250.00");
        defaults.put("minecraft:redstone", "5.00");
        defaults.put("minecraft:lapis_lazuli", "8.00");
        defaults.put("minecraft:quartz", "8.00");
        defaults.put("minecraft:obsidian", "130.00");
        defaults.put("minecraft:iron_block", "190.00");
        defaults.put("minecraft:gold_block", "450.00");
        defaults.put("minecraft:diamond_block", "2000.00");
        defaults.put("minecraft:emerald_block", "1800.00");
        defaults.put("minecraft:wheat", "5.00");
        defaults.put("minecraft:wheat_seeds", "2.00");
        defaults.put("minecraft:carrot", "5.00");
        defaults.put("minecraft:potato", "5.00");
        defaults.put("minecraft:beetroot", "5.00");
        defaults.put("minecraft:melon_slice", "3.00");
        defaults.put("minecraft:pumpkin", "10.00");
        defaults.put("minecraft:sugar_cane", "4.00");
        defaults.put("minecraft:cactus", "10.00");
        defaults.put("minecraft:egg", "1.00");
        defaults.put("minecraft:feather", "3.00");
        defaults.put("minecraft:string", "5.00");
        defaults.put("minecraft:leather", "10.00");
        defaults.put("minecraft:slime_ball", "50.00");
        defaults.put("minecraft:gunpowder", "20.00");
        defaults.put("minecraft:bone", "3.00");
        defaults.put("minecraft:arrow", "3.50");
        defaults.put("minecraft:bread", "30.00");
        defaults.put("minecraft:cooked_beef", "8.00");
        defaults.put("minecraft:cooked_porkchop", "8.00");
        defaults.put("minecraft:cooked_chicken", "7.00");
        defaults.put("minecraft:cod", "5.00");
        defaults.put("minecraft:cooked_cod", "7.00");
        defaults.put("minecraft:salmon", "6.00");
        defaults.put("minecraft:cooked_salmon", "8.00");
        defaults.put("minecraft:golden_apple", "100.00");
        return defaults;
    }

    record WorthEntry(String itemId, BigDecimal amount) {
    }
}
