package com.vantablack4.essentials.commands.kit;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import com.vantablack4.essentials.EssentialsConfig;
import com.vantablack4.essentials.Messages;
import com.vantablack4.essentials.VantablackEssentialsMod;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class KitCommands {
    private static final String KIT_ARGUMENT = "kit";
    private static final String TARGET_ARGUMENT = "target";
    private static final String COOLDOWN_ARGUMENT = "cooldownSeconds";
    private static final int MAX_COOLDOWN_SECONDS = 31_536_000;

    private final Predicate<CommandSourceStack> admin;
    private final KitStore store;
    private final KitCooldownStore cooldowns;

    public KitCommands(EssentialsConfig config, Predicate<CommandSourceStack> admin) {
        this.admin = admin;
        this.store = new KitStore(config.configDirectory());
        this.cooldowns = new KitCooldownStore(config.configDirectory());
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerAll(dispatcher, this::kitCommand, "kit", "ekit", "kits", "ekits");
        registerAll(dispatcher, this::createKitCommand, "createkit", "ecreatekit", "kitcreate", "createk", "kc", "ck");
        registerAll(dispatcher, this::deleteKitCommand, "delkit", "edelkit", "removekit", "eremovekit", "remkit", "eremkit", "rmkit", "ermkit", "deletekit", "edeletekit");
        registerAll(dispatcher, this::showKitCommand, "showkit", "eshowkit", "kitpreview", "preview", "kitshow");
        registerAll(dispatcher, this::kitResetCommand, "kitreset", "ekitreset", "kitr", "ekitr", "resetkit", "eresetkit");
    }

    private void registerAll(
        CommandDispatcher<CommandSourceStack> dispatcher,
        Function<String, LiteralArgumentBuilder<CommandSourceStack>> factory,
        String... names
    ) {
        for (String name : names) {
            dispatcher.register(factory.apply(name));
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> kitCommand(String name) {
        return Commands.literal(name)
            .executes(this::listKits)
            .then(Commands.argument(KIT_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestKits)
                .executes(context -> giveKit(context, getString(context, KIT_ARGUMENT), context.getSource().getPlayerOrException()))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .requires(admin)
                    .suggests(this::suggestPlayers)
                    .executes(context -> {
                        ServerPlayer target = resolveTarget(context.getSource().getServer(), getString(context, TARGET_ARGUMENT));
                        return target == null ? 0 : giveKit(context, getString(context, KIT_ARGUMENT), target);
                    })));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createKitCommand(String name) {
        return Commands.literal(name)
            .requires(admin)
            .then(Commands.argument(KIT_ARGUMENT, StringArgumentType.word())
                .executes(context -> createKit(context, getString(context, KIT_ARGUMENT), 0))
                .then(Commands.argument(COOLDOWN_ARGUMENT, IntegerArgumentType.integer(0, MAX_COOLDOWN_SECONDS))
                    .executes(context -> createKit(context, getString(context, KIT_ARGUMENT), getInteger(context, COOLDOWN_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> deleteKitCommand(String name) {
        return Commands.literal(name)
            .requires(admin)
            .then(Commands.argument(KIT_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestKits)
                .executes(context -> deleteKit(context, getString(context, KIT_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> showKitCommand(String name) {
        return Commands.literal(name)
            .then(Commands.argument(KIT_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestKits)
                .executes(context -> showKit(context, getString(context, KIT_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> kitResetCommand(String name) {
        return Commands.literal(name)
            .requires(admin)
            .then(Commands.argument(KIT_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestKits)
                .executes(context -> kitReset(context, getString(context, KIT_ARGUMENT), context.getSource().getPlayerOrException()))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestPlayers)
                    .executes(context -> {
                        ServerPlayer target = resolveTarget(context.getSource().getServer(), getString(context, TARGET_ARGUMENT));
                        return target == null ? 0 : kitReset(context, getString(context, KIT_ARGUMENT), target);
                    })));
    }

    private int listKits(CommandContext<CommandSourceStack> context) {
        List<String> kits = store.names(context.getSource().getServer().registryAccess());
        if (kits.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("No kits are configured."));
            return 0;
        }
        context.getSource().sendSystemMessage(Messages.line("Kits", String.join(", ", kits)));
        return kits.size();
    }

    private int createKit(CommandContext<CommandSourceStack> context, String rawName, int cooldownSeconds) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String name = safeName(rawName);
        if (name.isBlank()) {
            context.getSource().sendSystemMessage(Messages.error("Invalid kit name: " + rawName));
            return 0;
        }

        List<ItemStack> items = snapshotInventory(player);
        if (items.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Cannot create an empty kit."));
            return 0;
        }

        Kit kit = new Kit(name, cooldownSeconds, player.getUUID(), Instant.now(), items);
        if (!store.save(kit, context.getSource().getServer().registryAccess())) {
            context.getSource().sendSystemMessage(Messages.error("Unable to save kit: " + name));
            return 0;
        }
        context.getSource().sendSystemMessage(Messages.success("Kit saved: " + name + " (" + items.size() + " item stacks, cooldown " + cooldownSeconds + "s)"));
        return items.size();
    }

    private int deleteKit(CommandContext<CommandSourceStack> context, String rawName) {
        String name = safeName(rawName);
        if (store.delete(name)) {
            cooldowns.removeKit(name);
            context.getSource().sendSystemMessage(Messages.success("Kit deleted: " + name));
            return 1;
        }
        context.getSource().sendSystemMessage(Messages.error("Kit not found: " + rawName));
        return 0;
    }

    private int showKit(CommandContext<CommandSourceStack> context, String rawName) {
        Optional<Kit> loaded = store.load(safeName(rawName), context.getSource().getServer().registryAccess());
        if (loaded.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Kit not found: " + rawName));
            return 0;
        }

        Kit kit = loaded.get();
        context.getSource().sendSystemMessage(Messages.header("Kit " + kit.name()));
        context.getSource().sendSystemMessage(Messages.line("Cooldown", kit.cooldownSeconds() + " seconds"));
        if (kit.items().isEmpty()) {
            context.getSource().sendSystemMessage(Component.literal("(empty)"));
            return 0;
        }
        int index = 1;
        for (ItemStack stack : kit.items()) {
            context.getSource().sendSystemMessage(Messages.line(index + ".", stack.getCount() + " x " + stack.getHoverName().getString()));
            index++;
        }
        return kit.items().size();
    }

    private int giveKit(CommandContext<CommandSourceStack> context, String rawName, ServerPlayer target) throws CommandSyntaxException {
        Optional<Kit> loaded = store.load(safeName(rawName), context.getSource().getServer().registryAccess());
        if (loaded.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Kit not found: " + rawName));
            return 0;
        }

        Kit kit = loaded.get();
        ServerPlayer sourcePlayer = context.getSource().getPlayer();
        boolean enforceCooldown = sourcePlayer != null && sourcePlayer.getUUID().equals(target.getUUID()) && !admin.test(context.getSource());
        if (enforceCooldown) {
            Duration remaining = cooldowns.remaining(target.getUUID(), kit);
            if (!remaining.isZero() && !remaining.isNegative()) {
                context.getSource().sendSystemMessage(Messages.error("Kit cooldown remaining: " + formatDuration(remaining)));
                return 0;
            }
        }

        int delivered = 0;
        for (ItemStack stored : kit.items()) {
            ItemStack copy = stored.copy();
            target.getInventory().add(copy);
            if (!copy.isEmpty()) {
                target.drop(copy, false, false);
            }
            delivered++;
        }
        target.getInventory().setChanged();
        target.containerMenu.broadcastChanges();
        if (enforceCooldown) {
            cooldowns.markUsed(target.getUUID(), kit.name());
        }

        context.getSource().sendSystemMessage(Messages.success("Kit delivered: " + kit.name() + " -> " + displayName(target)));
        if (sourcePlayer != target) {
            target.sendSystemMessage(Messages.success("You received kit: " + kit.name()));
        }
        return delivered;
    }

    private int kitReset(CommandContext<CommandSourceStack> context, String rawName, ServerPlayer target) {
        String name = safeName(rawName);
        if (!store.exists(name)) {
            context.getSource().sendSystemMessage(Messages.error("Kit not found: " + rawName));
            return 0;
        }
        if (cooldowns.reset(target.getUUID(), name)) {
            context.getSource().sendSystemMessage(Messages.success("Kit cooldown reset: " + name + " -> " + displayName(target)));
            return 1;
        }
        context.getSource().sendSystemMessage(Messages.success("No active cooldown for kit: " + name + " -> " + displayName(target)));
        return 1;
    }

    private CompletableFuture<Suggestions> suggestKits(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(store.names(context.getSource().getServer().registryAccess()), builder);
    }

    private CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(playerSuggestions(context.getSource().getServer()), builder);
    }

    private static List<ItemStack> snapshotInventory(ServerPlayer player) {
        List<ItemStack> items = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                items.add(stack.copy());
            }
        }
        return items;
    }

    private static ServerPlayer resolveTarget(MinecraftServer server, String rawTarget) {
        String target = stripWrappingQuotes(rawTarget).trim();
        if (target.isBlank()) {
            return null;
        }

        ServerPlayer byName = server.getPlayerList().getPlayer(target);
        if (byName != null) {
            return byName;
        }

        String folded = target.toLowerCase(Locale.ROOT);
        return server.getPlayerList().getPlayers().stream()
            .filter(player -> displayName(player).equalsIgnoreCase(target) || player.getScoreboardName().toLowerCase(Locale.ROOT).equals(folded))
            .findFirst()
            .orElse(null);
    }

    private static Set<String> playerSuggestions(MinecraftServer server) {
        Set<String> suggestions = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            suggestions.add(player.getScoreboardName());
            suggestions.add(quoteIfNeeded(displayName(player)));
        }
        return suggestions;
    }

    private static String displayName(ServerPlayer player) {
        return player.getDisplayName().getString();
    }

    private static String stripWrappingQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"");
        }
        return value;
    }

    private static String quoteIfNeeded(String value) {
        return value.indexOf(' ') < 0 ? value : "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static String safeName(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        while (normalized.contains("__")) {
            normalized = normalized.replace("__", "_");
        }
        return normalized.replaceAll("^_+|_+$", "");
    }

    private static String formatDuration(Duration duration) {
        long seconds = Math.max(0L, duration.toSeconds());
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainingSeconds = seconds % 60L;
        if (hours > 0) {
            return hours + "h " + minutes + "m " + remainingSeconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + remainingSeconds + "s";
        }
        return remainingSeconds + "s";
    }

    private record Kit(
        String name,
        int cooldownSeconds,
        UUID creator,
        Instant createdAt,
        List<ItemStack> items
    ) {
    }

    private static final class KitStore {
        private final Path kitDirectory;

        private KitStore(Path configDirectory) {
            this.kitDirectory = configDirectory.resolve("kits");
        }

        private synchronized boolean save(Kit kit, HolderLookup.Provider lookup) {
            try {
                Files.createDirectories(kitDirectory);
                CompoundTag tag = new CompoundTag();
                tag.putString("name", kit.name());
                tag.putInt("cooldownSeconds", kit.cooldownSeconds());
                tag.putString("creator", kit.creator().toString());
                tag.putLong("createdAt", kit.createdAt().toEpochMilli());

                var ops = lookup.createSerializationContext(NbtOps.INSTANCE);
                ListTag items = new ListTag();
                int slot = 0;
                for (ItemStack stack : kit.items()) {
                    CompoundTag entry = new CompoundTag();
                    entry.putInt("slot", slot);
                    entry.store("stack", ItemStack.OPTIONAL_CODEC, ops, stack);
                    items.add(entry);
                    slot++;
                }
                tag.put("items", items);

                Path target = kitFile(kit.name());
                Path temporaryFile = Files.createTempFile(kitDirectory, kit.name() + ".", ".tmp");
                NbtIo.writeCompressed(tag, temporaryFile);
                moveReplacing(temporaryFile, target);
                return true;
            } catch (Exception exception) {
                VantablackEssentialsMod.LOGGER.warn("Unable to save Essentials kit {}", kit.name(), exception);
                return false;
            }
        }

        private synchronized Optional<Kit> load(String name, HolderLookup.Provider lookup) {
            if (name.isBlank()) {
                return Optional.empty();
            }
            Path file = kitFile(name);
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try {
                CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                String kitName = safeName(tag.getStringOr("name", name));
                int cooldownSeconds = Math.max(0, tag.getIntOr("cooldownSeconds", 0));
                UUID creator = parseUuid(tag.getStringOr("creator", "00000000-0000-0000-0000-000000000000"));
                Instant createdAt = Instant.ofEpochMilli(tag.getLongOr("createdAt", 0L));

                var ops = lookup.createSerializationContext(NbtOps.INSTANCE);
                List<ItemStack> items = new ArrayList<>();
                for (CompoundTag entry : tag.getListOrEmpty("items").compoundStream().toList()) {
                    ItemStack stack = entry.read("stack", ItemStack.OPTIONAL_CODEC, ops).orElse(ItemStack.EMPTY);
                    if (!stack.isEmpty()) {
                        items.add(stack);
                    }
                }
                return Optional.of(new Kit(kitName, cooldownSeconds, creator, createdAt, items));
            } catch (Exception exception) {
                VantablackEssentialsMod.LOGGER.warn("Unable to load Essentials kit {}", name, exception);
                return Optional.empty();
            }
        }

        private synchronized List<String> names(HolderLookup.Provider lookup) {
            if (!Files.isDirectory(kitDirectory)) {
                return List.of();
            }
            try (var files = Files.list(kitDirectory)) {
                return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".dat"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.dat$", ""))
                    .map(name -> load(name, lookup).map(Kit::name).orElse(name))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            } catch (IOException exception) {
                VantablackEssentialsMod.LOGGER.warn("Unable to list Essentials kits", exception);
                return List.of();
            }
        }

        private synchronized boolean exists(String name) {
            return Files.isRegularFile(kitFile(name));
        }

        private synchronized boolean delete(String name) {
            try {
                return Files.deleteIfExists(kitFile(name));
            } catch (IOException exception) {
                VantablackEssentialsMod.LOGGER.warn("Unable to delete Essentials kit {}", name, exception);
                return false;
            }
        }

        private Path kitFile(String name) {
            return kitDirectory.resolve(safeName(name) + ".dat");
        }
    }

    private static final class KitCooldownStore {
        private final Path cooldownFile;
        private final Properties properties;

        private KitCooldownStore(Path configDirectory) {
            this.cooldownFile = configDirectory.resolve("kit-cooldowns.properties");
            this.properties = loadProperties(cooldownFile);
        }

        private synchronized Duration remaining(UUID playerUuid, Kit kit) {
            if (kit.cooldownSeconds() <= 0) {
                return Duration.ZERO;
            }
            long lastUsed = lastUsed(playerUuid, kit.name());
            if (lastUsed <= 0L) {
                return Duration.ZERO;
            }
            Instant availableAt = Instant.ofEpochMilli(lastUsed).plusSeconds(kit.cooldownSeconds());
            return Duration.between(Instant.now(), availableAt);
        }

        private synchronized void markUsed(UUID playerUuid, String kitName) {
            properties.setProperty(key(playerUuid, kitName), Long.toString(System.currentTimeMillis()));
            saveProperties(cooldownFile, properties, "Vantablack Essentials kit cooldowns");
        }

        private synchronized boolean reset(UUID playerUuid, String kitName) {
            Object removed = properties.remove(key(playerUuid, kitName));
            if (removed != null) {
                saveProperties(cooldownFile, properties, "Vantablack Essentials kit cooldowns");
                return true;
            }
            return false;
        }

        private synchronized void removeKit(String kitName) {
            String suffix = "." + safeName(kitName);
            List<String> keys = properties.stringPropertyNames().stream()
                .filter(key -> key.endsWith(suffix))
                .toList();
            keys.forEach(properties::remove);
            if (!keys.isEmpty()) {
                saveProperties(cooldownFile, properties, "Vantablack Essentials kit cooldowns");
            }
        }

        private long lastUsed(UUID playerUuid, String kitName) {
            try {
                return Long.parseLong(properties.getProperty(key(playerUuid, kitName), "0"));
            } catch (NumberFormatException exception) {
                return 0L;
            }
        }

        private static String key(UUID playerUuid, String kitName) {
            return playerUuid + "." + safeName(kitName);
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return new UUID(0L, 0L);
        }
    }

    private static Properties loadProperties(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to load Essentials properties: {}", file, exception);
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
            moveReplacing(temporaryFile, file);
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to save Essentials properties: {}", file, exception);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
