package com.vantablack4.essentials.commands.item;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;

import com.vantablack4.essentials.Messages;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

public final class EssentialsItemCommands {
    private static final String ITEM_ARGUMENT = "item";
    private static final String TARGET_ARGUMENT = "target";
    private static final String COUNT_ARGUMENT = "count";
    private static final String NAME_ARGUMENT = "name";
    private static final String TEXT_ARGUMENT = "text";
    private static final String LINE_ARGUMENT = "line";
    private static final String ENCHANTMENT_ARGUMENT = "enchantment";
    private static final String EFFECT_ARGUMENT = "effect";
    private static final String LEVEL_ARGUMENT = "level";
    private static final String POWER_ARGUMENT = "power";
    private static final String DURATION_ARGUMENT = "duration";
    private static final String TITLE_ARGUMENT = "title";
    private static final String AUTHOR_ARGUMENT = "author";
    private static final String OWNER_ARGUMENT = "owner";
    private static final String PAGE_ARGUMENT = "page";
    private static final int MAX_GIVE_COUNT = 6400;
    private static final int MAX_AUTHOR_LENGTH = 64;
    private static final List<String> REPAIR_ROOTS = List.of("repair", "fix", "efix", "erepair");

    private EssentialsItemCommands() {
    }

    public static void register(
        CommandDispatcher<CommandSourceStack> dispatcher,
        CommandBuildContext registryAccess,
        Predicate<CommandSourceStack> admin
    ) {
        registerAll(dispatcher, name -> moreCommand(name, admin), "more", "emore");
        registerAll(dispatcher, name -> itemDbCommand(name, registryAccess, admin), "itemdb", "dura", "edura", "durability", "edurability", "eitemdb", "iteminfo", "eiteminfo", "itemno", "eitemno");
        registerAll(dispatcher, name -> giveCommand(name, registryAccess, admin), "give", "egive");
        registerAll(dispatcher, name -> itemCommand(name, registryAccess, admin), "item", "i", "eitem", "ei");
        registerAll(dispatcher, name -> enchantCommand(name, registryAccess, admin), "enchant", "eenchant", "enchantment", "eenchantment");
        registerAll(dispatcher, name -> itemNameCommand(name, admin), "itemname", "eitemname", "iname", "einame", "itemrename", "irename", "eitemrename", "eirename");
        registerAll(dispatcher, name -> itemLoreCommand(name, admin), "itemlore", "eitemlore", "lore", "elore", "ilore", "eilore");
        registerAll(dispatcher, name -> bookCommand(name, admin), "book", "ebook");
        registerAll(dispatcher, name -> potionCommand(name, registryAccess, admin), "potion", "epotion", "elixer", "eelixer");
        registerAll(dispatcher, name -> recipeCommand(name, registryAccess), "recipe", "formula", "eformula", "method", "emethod", "erecipe", "recipes", "erecipes");
        registerAll(dispatcher, name -> skullCommand(name, admin), "skull", "eskull", "playerskull", "eplayerskull");
        appendRepairModeBranches(dispatcher, admin);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> moreCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name)
            .requires(admin)
            .executes(context -> more(context, null))
            .then(Commands.argument(COUNT_ARGUMENT, IntegerArgumentType.integer(1))
                .executes(context -> more(context, getInteger(context, COUNT_ARGUMENT))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> itemDbCommand(
        String name,
        CommandBuildContext registryAccess,
        Predicate<CommandSourceStack> admin
    ) {
        return Commands.literal(name)
            .requires(admin)
            .executes(EssentialsItemCommands::itemDbHeld)
            .then(Commands.argument(ITEM_ARGUMENT, ItemArgument.item(registryAccess))
                .executes(EssentialsItemCommands::itemDbArgument));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> giveCommand(
        String name,
        CommandBuildContext registryAccess,
        Predicate<CommandSourceStack> admin
    ) {
        return Commands.literal(name)
            .requires(admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(EssentialsItemCommands::suggestPlayers)
                .then(Commands.argument(ITEM_ARGUMENT, ItemArgument.item(registryAccess))
                    .executes(context -> give(context, resolveTarget(context, TARGET_ARGUMENT), ItemArgument.getItem(context, ITEM_ARGUMENT), 1))
                    .then(Commands.argument(COUNT_ARGUMENT, IntegerArgumentType.integer(1, MAX_GIVE_COUNT))
                        .executes(context -> give(
                            context,
                            resolveTarget(context, TARGET_ARGUMENT),
                            ItemArgument.getItem(context, ITEM_ARGUMENT),
                            getInteger(context, COUNT_ARGUMENT)
                        )))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> itemCommand(
        String name,
        CommandBuildContext registryAccess,
        Predicate<CommandSourceStack> admin
    ) {
        return Commands.literal(name)
            .requires(admin)
            .then(Commands.argument(ITEM_ARGUMENT, ItemArgument.item(registryAccess))
                .executes(context -> give(context, context.getSource().getPlayerOrException(), ItemArgument.getItem(context, ITEM_ARGUMENT), 1))
                .then(Commands.argument(COUNT_ARGUMENT, IntegerArgumentType.integer(1, MAX_GIVE_COUNT))
                    .executes(context -> give(
                        context,
                        context.getSource().getPlayerOrException(),
                        ItemArgument.getItem(context, ITEM_ARGUMENT),
                        getInteger(context, COUNT_ARGUMENT)
                    ))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> enchantCommand(
        String name,
        CommandBuildContext registryAccess,
        Predicate<CommandSourceStack> admin
    ) {
        return Commands.literal(name)
            .requires(admin)
            .then(Commands.argument(ENCHANTMENT_ARGUMENT, ResourceArgument.resource(registryAccess, Registries.ENCHANTMENT))
                .executes(context -> enchant(context, ResourceArgument.getEnchantment(context, ENCHANTMENT_ARGUMENT), 1))
                .then(Commands.argument(LEVEL_ARGUMENT, IntegerArgumentType.integer(0))
                    .executes(context -> enchant(
                        context,
                        ResourceArgument.getEnchantment(context, ENCHANTMENT_ARGUMENT),
                        getInteger(context, LEVEL_ARGUMENT)
                    ))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> itemNameCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name)
            .requires(admin)
            .then(Commands.literal("clear")
                .executes(EssentialsItemCommands::clearItemName))
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.greedyString())
                .executes(EssentialsItemCommands::setItemName));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> itemLoreCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name)
            .requires(admin)
            .then(Commands.literal("add")
                .then(Commands.argument(TEXT_ARGUMENT, StringArgumentType.greedyString())
                    .executes(EssentialsItemCommands::addItemLore)))
            .then(Commands.literal("set")
                .then(Commands.argument(LINE_ARGUMENT, IntegerArgumentType.integer(1))
                    .then(Commands.argument(TEXT_ARGUMENT, StringArgumentType.greedyString())
                        .executes(EssentialsItemCommands::setItemLore))))
            .then(Commands.literal("remove")
                .then(Commands.argument(LINE_ARGUMENT, IntegerArgumentType.integer(1))
                    .executes(EssentialsItemCommands::removeItemLore)))
            .then(Commands.literal("clear")
                .executes(EssentialsItemCommands::clearItemLore));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> bookCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name)
            .requires(admin)
            .then(Commands.literal("title")
                .then(Commands.argument(TITLE_ARGUMENT, StringArgumentType.greedyString())
                    .executes(EssentialsItemCommands::bookTitle)))
            .then(Commands.literal("author")
                .then(Commands.argument(AUTHOR_ARGUMENT, StringArgumentType.greedyString())
                    .executes(EssentialsItemCommands::bookAuthor)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> potionCommand(
        String name,
        CommandBuildContext registryAccess,
        Predicate<CommandSourceStack> admin
    ) {
        return Commands.literal(name)
            .requires(admin)
            .then(Commands.literal("clear")
                .executes(EssentialsItemCommands::clearPotion))
            .then(Commands.argument(EFFECT_ARGUMENT, ResourceArgument.resource(registryAccess, Registries.MOB_EFFECT))
                .executes(context -> setPotion(context, ResourceArgument.getMobEffect(context, EFFECT_ARGUMENT), 1, 60))
                .then(Commands.argument(LEVEL_ARGUMENT, IntegerArgumentType.integer(1, 255))
                    .executes(context -> setPotion(
                        context,
                        ResourceArgument.getMobEffect(context, EFFECT_ARGUMENT),
                        getInteger(context, LEVEL_ARGUMENT),
                        60
                    ))
                    .then(Commands.argument(DURATION_ARGUMENT, IntegerArgumentType.integer(1, 86_400))
                        .executes(context -> setPotion(
                            context,
                            ResourceArgument.getMobEffect(context, EFFECT_ARGUMENT),
                            getInteger(context, LEVEL_ARGUMENT),
                            getInteger(context, DURATION_ARGUMENT)
                        )))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> recipeCommand(String name, CommandBuildContext registryAccess) {
        return Commands.literal(name)
            .then(Commands.argument(ITEM_ARGUMENT, ItemArgument.item(registryAccess))
                .executes(context -> recipe(context, ItemArgument.getItem(context, ITEM_ARGUMENT), 1))
                .then(Commands.argument(PAGE_ARGUMENT, IntegerArgumentType.integer(1))
                    .executes(context -> recipe(context, ItemArgument.getItem(context, ITEM_ARGUMENT), getInteger(context, PAGE_ARGUMENT)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> skullCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name)
            .requires(admin)
            .executes(context -> skull(context, context.getSource().getPlayerOrException().getScoreboardName(), context.getSource().getPlayerOrException()))
            .then(Commands.argument(OWNER_ARGUMENT, StringArgumentType.string())
                .executes(context -> skull(context, getString(context, OWNER_ARGUMENT), context.getSource().getPlayerOrException()))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .suggests(EssentialsItemCommands::suggestPlayers)
                    .executes(context -> skull(context, getString(context, OWNER_ARGUMENT), resolveTarget(context, TARGET_ARGUMENT)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> repairCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name)
            .requires(admin)
            .executes(context -> repair(context, context.getSource().getPlayerOrException(), RepairMode.HAND))
            .then(repairModeBranch("hand", RepairMode.HAND))
            .then(repairModeBranch("all", RepairMode.ALL));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> repairModeBranch(String name, RepairMode mode) {
        return Commands.literal(name)
            .executes(context -> repair(context, context.getSource().getPlayerOrException(), mode))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(EssentialsItemCommands::suggestPlayers)
                .executes(context -> repair(context, resolveTarget(context, TARGET_ARGUMENT), mode)));
    }

    private static void appendRepairModeBranches(CommandDispatcher<CommandSourceStack> dispatcher, Predicate<CommandSourceStack> admin) {
        for (String name : REPAIR_ROOTS) {
            CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild(name);
            if (root == null) {
                dispatcher.register(repairCommand(name, admin));
                continue;
            }
            root.addChild(repairModeBranch("hand", RepairMode.HAND).build());
            root.addChild(repairModeBranch("all", RepairMode.ALL).build());
        }
    }

    private static void registerAll(
        CommandDispatcher<CommandSourceStack> dispatcher,
        CommandFactory factory,
        String... names
    ) {
        for (String name : names) {
            dispatcher.register(factory.create(name));
        }
    }

    private static int more(CommandContext<CommandSourceStack> context, Integer requestedCount) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = requireHeldStack(context, player);
        if (stack.isEmpty()) {
            return 0;
        }
        int maxCount = stack.getMaxStackSize();
        int desiredCount = requestedCount == null ? maxCount : Math.min(requestedCount, maxCount);
        if (stack.getCount() >= desiredCount) {
            context.getSource().sendSystemMessage(Messages.error("Eldeki eşya zaten " + stack.getCount() + " adet."));
            return 0;
        }
        stack.setCount(desiredCount);
        player.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Eldeki eşya " + desiredCount + " adede tamamlandı: " + stack.getHoverName().getString()));
        return desiredCount;
    }

    private static int itemDbHeld(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = requireHeldStack(context, player);
        if (stack.isEmpty()) {
            return 0;
        }
        describeItem(context.getSource(), stack);
        return 1;
    }

    private static int itemDbArgument(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ItemStack stack = ItemArgument.getItem(context, ITEM_ARGUMENT).createItemStack(1);
        if (stack.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Geçersiz eşya."));
            return 0;
        }
        describeItem(context.getSource(), stack);
        return 1;
    }

    private static int give(
        CommandContext<CommandSourceStack> context,
        ServerPlayer target,
        ItemInput itemInput,
        int count
    ) throws CommandSyntaxException {
        if (target == null) {
            return 0;
        }
        ItemStack probe = itemInput.createItemStack(1);
        if (probe.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Verilecek eşya boş olamaz."));
            return 0;
        }

        int remaining = count;
        int maxStackSize = probe.getMaxStackSize();
        while (remaining > 0) {
            int chunkSize = Math.min(maxStackSize, remaining);
            ItemStack chunk = itemInput.createItemStack(chunkSize);
            target.getInventory().add(chunk);
            if (!chunk.isEmpty()) {
                target.drop(chunk, false, false);
            }
            remaining -= chunkSize;
        }
        target.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success(count + " x " + itemId(probe) + " verildi: " + displayName(target)));
        if (context.getSource().getPlayer() != target) {
            target.sendSystemMessage(Messages.success(count + " x " + probe.getHoverName().getString() + " aldın."));
        }
        return count;
    }

    private static int enchant(
        CommandContext<CommandSourceStack> context,
        Holder.Reference<Enchantment> enchantment,
        int level
    ) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = requireHeldStack(context, player);
        if (stack.isEmpty()) {
            return 0;
        }
        if (level > enchantment.value().getMaxLevel()) {
            context.getSource().sendSystemMessage(Messages.error("Bu büyünün maksimum seviyesi " + enchantment.value().getMaxLevel() + "."));
            return 0;
        }
        if (level > 0 && !enchantment.value().canEnchant(stack)) {
            context.getSource().sendSystemMessage(Messages.error("Bu büyü eldeki eşyaya uygulanamaz: " + enchantment.key().identifier()));
            return 0;
        }
        if (level > 0 && !EnchantmentHelper.isEnchantmentCompatible(
            EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet().stream()
                .filter(existing -> !existing.equals(enchantment))
                .toList(),
            enchantment
        )) {
            context.getSource().sendSystemMessage(Messages.error("Bu büyü eldeki eşyanın mevcut büyüleriyle uyumsuz: " + enchantment.key().identifier()));
            return 0;
        }

        if (level == 0) {
            EnchantmentHelper.updateEnchantments(stack, mutable -> mutable.removeIf(holder -> holder.equals(enchantment)));
            context.getSource().sendSystemMessage(Messages.success("Büyü kaldırıldı: " + enchantment.key().identifier()));
        } else {
            EnchantmentHelper.updateEnchantments(stack, mutable -> mutable.set(enchantment, level));
            context.getSource().sendSystemMessage(Messages.success("Büyü uygulandı: " + enchantment.key().identifier() + " " + level));
        }
        player.containerMenu.broadcastChanges();
        return 1;
    }

    private static int setItemName(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = requireHeldStack(context, player);
        if (stack.isEmpty()) {
            return 0;
        }
        String value = getString(context, NAME_ARGUMENT).trim();
        if (value.isBlank()) {
            context.getSource().sendSystemMessage(Messages.error("Eşya adı boş olamaz."));
            return 0;
        }
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(value));
        player.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Eşya adı ayarlandı: " + value));
        return 1;
    }

    private static int clearItemName(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = requireHeldStack(context, player);
        if (stack.isEmpty()) {
            return 0;
        }
        stack.remove(DataComponents.CUSTOM_NAME);
        player.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Eşya adı temizlendi."));
        return 1;
    }

    private static int addItemLore(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = requireHeldStack(context, player);
        if (stack.isEmpty()) {
            return 0;
        }
        List<Component> lines = loreLines(stack);
        if (lines.size() >= ItemLore.MAX_LINES) {
            context.getSource().sendSystemMessage(Messages.error("Eşya lore satır sınırına ulaştı: " + ItemLore.MAX_LINES));
            return 0;
        }
        lines.add(loreComponent(getString(context, TEXT_ARGUMENT)));
        setLore(player, stack, lines);
        context.getSource().sendSystemMessage(Messages.success("Lore satırı eklendi."));
        return 1;
    }

    private static int setItemLore(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = requireHeldStack(context, player);
        if (stack.isEmpty()) {
            return 0;
        }
        int line = getInteger(context, LINE_ARGUMENT);
        List<Component> lines = loreLines(stack);
        if (line > lines.size()) {
            context.getSource().sendSystemMessage(Messages.error("Lore satırı bulunamadı: " + line));
            return 0;
        }
        lines.set(line - 1, loreComponent(getString(context, TEXT_ARGUMENT)));
        setLore(player, stack, lines);
        context.getSource().sendSystemMessage(Messages.success("Lore satırı güncellendi: " + line));
        return 1;
    }

    private static int removeItemLore(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = requireHeldStack(context, player);
        if (stack.isEmpty()) {
            return 0;
        }
        int line = getInteger(context, LINE_ARGUMENT);
        List<Component> lines = loreLines(stack);
        if (line > lines.size()) {
            context.getSource().sendSystemMessage(Messages.error("Lore satırı bulunamadı: " + line));
            return 0;
        }
        lines.remove(line - 1);
        setLore(player, stack, lines);
        context.getSource().sendSystemMessage(Messages.success("Lore satırı silindi: " + line));
        return 1;
    }

    private static int clearItemLore(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = requireHeldStack(context, player);
        if (stack.isEmpty()) {
            return 0;
        }
        stack.remove(DataComponents.LORE);
        player.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Lore temizlendi."));
        return 1;
    }

    private static int bookTitle(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String title = getString(context, TITLE_ARGUMENT).trim();
        if (title.isBlank()) {
            context.getSource().sendSystemMessage(Messages.error("Kitap başlığı boş olamaz."));
            return 0;
        }
        if (title.length() > WrittenBookContent.TITLE_MAX_LENGTH) {
            context.getSource().sendSystemMessage(Messages.error("Kitap başlığı en fazla " + WrittenBookContent.TITLE_MAX_LENGTH + " karakter olabilir."));
            return 0;
        }
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack book = requireWritableOrWrittenBook(context, player);
        if (book.isEmpty()) {
            return 0;
        }
        WrittenBookContent current = writtenBookContent(book, player);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
            Filterable.passThrough(title),
            current.author(),
            current.generation(),
            current.pages(),
            current.resolved()
        ));
        player.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Kitap başlığı ayarlandı: " + title));
        return 1;
    }

    private static int bookAuthor(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String author = getString(context, AUTHOR_ARGUMENT).trim();
        if (author.isBlank()) {
            context.getSource().sendSystemMessage(Messages.error("Kitap yazarı boş olamaz."));
            return 0;
        }
        if (author.length() > MAX_AUTHOR_LENGTH) {
            context.getSource().sendSystemMessage(Messages.error("Kitap yazarı en fazla " + MAX_AUTHOR_LENGTH + " karakter olabilir."));
            return 0;
        }
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack book = requireWritableOrWrittenBook(context, player);
        if (book.isEmpty()) {
            return 0;
        }
        WrittenBookContent current = writtenBookContent(book, player);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
            current.title(),
            author,
            current.generation(),
            current.pages(),
            current.resolved()
        ));
        player.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Kitap yazarı ayarlandı: " + author));
        return 1;
    }

    private static int setPotion(
        CommandContext<CommandSourceStack> context,
        Holder.Reference<MobEffect> effect,
        int level,
        int durationSeconds
    ) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = requirePotionStack(context, player);
        if (stack.isEmpty()) {
            return 0;
        }

        MobEffectInstance instance = new MobEffectInstance(effect, durationSeconds * 20, Math.max(0, level - 1));
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.empty(), List.of(instance), Optional.empty()));
        player.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success(
            "Potion effect set: " + effect.key().identifier() + " " + level + " for " + durationSeconds + "s."
        ));
        return 1;
    }

    private static int clearPotion(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = requirePotionStack(context, player);
        if (stack.isEmpty()) {
            return 0;
        }
        stack.remove(DataComponents.POTION_CONTENTS);
        player.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Potion effects cleared."));
        return 1;
    }

    private static int recipe(CommandContext<CommandSourceStack> context, ItemInput itemInput, int page) throws CommandSyntaxException {
        ItemStack probe = itemInput.createItemStack(1);
        if (probe.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Invalid recipe item."));
            return 0;
        }

        Identifier itemKey = BuiltInRegistries.ITEM.getKey(probe.getItem());
        String itemId = itemKey == null ? probe.getItem().toString() : itemKey.toString();
        String itemPath = itemKey == null ? itemId : itemKey.getPath();
        String needle = itemPath.toLowerCase(Locale.ROOT);
        List<RecipeHolder<?>> matches = context.getSource().getServer().getRecipeManager().getRecipes().stream()
            .filter(holder -> recipeMatches(holder, itemId, needle))
            .sorted(Comparator.comparing(holder -> holder.id().identifier().toString()))
            .toList();

        if (matches.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("No known recipe matched " + itemId + "."));
            return 0;
        }

        int pageSize = 8;
        int maxPage = Math.max(1, (matches.size() + pageSize - 1) / pageSize);
        int currentPage = Math.min(page, maxPage);
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(matches.size(), start + pageSize);
        context.getSource().sendSystemMessage(Messages.line("Recipes", itemId + " page " + currentPage + "/" + maxPage));
        for (RecipeHolder<?> holder : matches.subList(start, end)) {
            Recipe<?> recipe = holder.value();
            context.getSource().sendSystemMessage(Component.literal(
                "- " + holder.id().identifier() + " [" + recipe.getType() + "]"
            ).withStyle(ChatFormatting.GRAY));
        }
        return end - start;
    }

    private static int skull(CommandContext<CommandSourceStack> context, String rawOwner, ServerPlayer target) throws CommandSyntaxException {
        if (target == null) {
            return 0;
        }
        String owner = stripWrappingQuotes(rawOwner).trim();
        if (owner.isBlank()) {
            context.getSource().sendSystemMessage(Messages.error("Skull owner cannot be blank."));
            return 0;
        }

        ItemStack skull = new ItemStack(Items.PLAYER_HEAD);
        skull.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(owner));
        target.getInventory().add(skull);
        if (!skull.isEmpty()) {
            target.drop(skull, false, false);
        }
        target.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Gave " + owner + "'s skull to " + displayName(target) + "."));
        if (context.getSource().getPlayer() != target) {
            target.sendSystemMessage(Messages.success("You received " + owner + "'s skull."));
        }
        return 1;
    }

    private static int clearFirework(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = requireHeldStack(context, player);
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.getItem() != Items.FIREWORK_ROCKET) {
            context.getSource().sendSystemMessage(Messages.error("Elindeki eşya havai fişek roketi olmalı."));
            return 0;
        }
        stack.remove(DataComponents.FIREWORKS);
        player.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Havai fişek verisi temizlendi."));
        return 1;
    }

    private static int setFireworkPower(CommandContext<CommandSourceStack> context, int power) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = requireHeldStack(context, player);
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.getItem() != Items.FIREWORK_ROCKET) {
            context.getSource().sendSystemMessage(Messages.error("Elindeki eşya havai fişek roketi olmalı."));
            return 0;
        }
        Fireworks current = stack.getOrDefault(DataComponents.FIREWORKS, new Fireworks(1, List.of()));
        stack.set(DataComponents.FIREWORKS, new Fireworks(power, current.explosions()));
        player.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Havai fişek gücü ayarlandı: " + power));
        return 1;
    }

    private static int makeFireworkStar(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack held = requireHeldStack(context, player);
        if (held.isEmpty()) {
            return 0;
        }
        FireworkExplosion explosion = FireworkExplosion.DEFAULT;
        Fireworks fireworks = held.get(DataComponents.FIREWORKS);
        if (fireworks != null && !fireworks.explosions().isEmpty()) {
            explosion = fireworks.explosions().getFirst();
        }
        ItemStack star = new ItemStack(Items.FIREWORK_STAR);
        star.set(DataComponents.FIREWORK_EXPLOSION, explosion);
        player.getInventory().add(star);
        if (!star.isEmpty()) {
            player.drop(star, false, false);
        }
        player.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Havai fişek yıldızı oluşturuldu."));
        return 1;
    }

    private static int repair(
        CommandContext<CommandSourceStack> context,
        ServerPlayer target,
        RepairMode mode
    ) throws CommandSyntaxException {
        if (target == null) {
            return 0;
        }
        return switch (mode) {
            case HAND -> repairHand(context, target);
            case ALL -> repairAll(context, target);
        };
    }

    private static int repairHand(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        ItemStack stack = target.getMainHandItem();
        if (stack.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Tamir edilecek elde eşya yok: " + displayName(target)));
            return 0;
        }
        if (!repairStack(stack)) {
            context.getSource().sendSystemMessage(Messages.error("Eldeki eşya tamir gerektirmiyor: " + displayName(target)));
            return 0;
        }
        target.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Eldeki eşya tamir edildi: " + displayName(target)));
        return 1;
    }

    private static int repairAll(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        Inventory inventory = target.getInventory();
        int repaired = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (repairStack(inventory.getItem(slot))) {
                repaired++;
            }
        }
        if (repaired == 0) {
            context.getSource().sendSystemMessage(Messages.error("Tamir edilecek hasarlı eşya bulunamadı: " + displayName(target)));
            return 0;
        }
        inventory.setChanged();
        target.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success(repaired + " eşya tamir edildi: " + displayName(target)));
        return repaired;
    }

    private static boolean repairStack(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem() || !stack.isDamaged()) {
            return false;
        }
        stack.setDamageValue(0);
        return true;
    }

    private static void describeItem(CommandSourceStack source, ItemStack stack) {
        source.sendSystemMessage(Messages.line("Item", itemId(stack)));
        source.sendSystemMessage(Messages.line("Name", stack.getHoverName().getString()));
        source.sendSystemMessage(Messages.line("Max stack", Integer.toString(stack.getMaxStackSize())));
        source.sendSystemMessage(Messages.line(
            "Components",
            stack.getComponentsPatch().isEmpty() ? "none" : stack.getComponentsPatch().toString()
        ));
    }

    private static List<Component> loreLines(ItemStack stack) {
        return new ArrayList<>(stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
    }

    private static Component loreComponent(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static void setLore(ServerPlayer player, ItemStack stack, List<Component> lines) {
        if (lines.isEmpty()) {
            stack.remove(DataComponents.LORE);
        } else {
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        player.containerMenu.broadcastChanges();
    }

    private static ItemStack requireHeldStack(CommandContext<CommandSourceStack> context, ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Elinde eşya yok."));
        }
        return stack;
    }

    private static ItemStack requirePotionStack(CommandContext<CommandSourceStack> context, ServerPlayer player) {
        ItemStack held = requireHeldStack(context, player);
        if (held.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (
            held.getItem() != Items.POTION
                && held.getItem() != Items.SPLASH_POTION
                && held.getItem() != Items.LINGERING_POTION
                && held.getItem() != Items.TIPPED_ARROW
        ) {
            context.getSource().sendSystemMessage(Messages.error("Hold a potion, splash potion, lingering potion, or tipped arrow first."));
            return ItemStack.EMPTY;
        }
        return held;
    }

    private static boolean recipeMatches(RecipeHolder<?> holder, String itemId, String itemPath) {
        String recipeId = holder.id().identifier().toString().toLowerCase(Locale.ROOT);
        if (recipeId.contains(itemId.toLowerCase(Locale.ROOT)) || recipeId.contains(itemPath)) {
            return true;
        }
        return holder.value().display().toString().toLowerCase(Locale.ROOT).contains(itemPath);
    }

    private static boolean isPotionCarrier(ItemStack stack) {
        return stack.getItem() == Items.POTION
            || stack.getItem() == Items.SPLASH_POTION
            || stack.getItem() == Items.LINGERING_POTION
            || stack.getItem() == Items.TIPPED_ARROW;
    }

    private static ItemStack requireWritableOrWrittenBook(CommandContext<CommandSourceStack> context, ServerPlayer player) {
        ItemStack held = requireHeldStack(context, player);
        if (held.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (held.getItem() == Items.WRITTEN_BOOK) {
            return held;
        }
        if (held.getItem() != Items.WRITABLE_BOOK) {
            context.getSource().sendSystemMessage(Messages.error("Elindeki eşya yazılabilir ya da imzalı kitap olmalı."));
            return ItemStack.EMPTY;
        }
        ItemStack written = held.transmuteCopy(Items.WRITTEN_BOOK, held.getCount());
        written.remove(DataComponents.WRITABLE_BOOK_CONTENT);
        player.getInventory().setSelectedItem(written);
        player.getInventory().setChanged();
        return written;
    }

    private static WrittenBookContent writtenBookContent(ItemStack book, ServerPlayer player) {
        WrittenBookContent written = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (written != null) {
            return written;
        }
        WritableBookContent writable = book.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
        List<Filterable<Component>> pages = writable.pages().stream()
            .map(page -> page.map(text -> (Component) Component.literal(text)))
            .toList();
        return new WrittenBookContent(
            Filterable.passThrough("Untitled"),
            displayName(player),
            0,
            pages,
            false
        );
    }

    private static ServerPlayer resolveTarget(CommandContext<CommandSourceStack> context, String argumentName) {
        String rawTarget = getString(context, argumentName);
        return findPlayer(context.getSource().getServer(), rawTarget)
            .orElseGet(() -> {
                context.getSource().sendSystemMessage(Messages.error("Oyuncu bulunamadı: " + rawTarget));
                return null;
            });
    }

    private static CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(playerSuggestions(context.getSource().getServer()), builder);
    }

    private static String itemId(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? stack.getItem().toString() : id.toString();
    }

    private static Optional<ServerPlayer> findPlayer(MinecraftServer server, String rawTarget) {
        String target = stripWrappingQuotes(rawTarget).trim();
        if (target.isBlank()) {
            return Optional.empty();
        }

        ServerPlayer byProfileName = server.getPlayerList().getPlayer(target);
        if (byProfileName != null) {
            return Optional.of(byProfileName);
        }

        String folded = target.toLowerCase(Locale.ROOT);
        return server.getPlayerList().getPlayers().stream()
            .filter(player -> displayName(player).equalsIgnoreCase(target) || player.getScoreboardName().toLowerCase(Locale.ROOT).equals(folded))
            .findFirst();
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
        if (value.indexOf(' ') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private enum RepairMode {
        HAND,
        ALL
    }

    @FunctionalInterface
    private interface CommandFactory {
        LiteralArgumentBuilder<CommandSourceStack> create(String name);
    }
}
