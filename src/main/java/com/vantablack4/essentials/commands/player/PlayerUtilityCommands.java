package com.vantablack4.essentials.commands.player;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;

import com.vantablack4.essentials.EssentialsConfig;
import com.vantablack4.essentials.Messages;
import com.vantablack4.essentials.VantablackEssentialsMod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class PlayerUtilityCommands {
    private static final String TARGET_ARGUMENT = "target";
    private static final String AMOUNT_ARGUMENT = "amount";
    private static final String TIME_ARGUMENT = "time";
    private static final String WEATHER_ARGUMENT = "weather";
    private static final String COMMAND_ARGUMENT = "command";
    private static final String TEXT_ARGUMENT = "text";
    private static final String PAGE_ARGUMENT = "page";
    private static final String ITEM_ARGUMENT = "item";
    private static final int HELP_PAGE_SIZE = 10;
    private static final int TEXT_PAGE_SIZE = 8;
    private static final int INVENTORY_VIEW_SIZE = 54;

    private static final List<CondenseRule> CONDENSE_RULES = List.of(
        new CondenseRule(Items.IRON_NUGGET, Items.IRON_INGOT),
        new CondenseRule(Items.GOLD_NUGGET, Items.GOLD_INGOT),
        new CondenseRule(Items.IRON_INGOT, Items.IRON_BLOCK),
        new CondenseRule(Items.GOLD_INGOT, Items.GOLD_BLOCK),
        new CondenseRule(Items.COPPER_INGOT, Items.COPPER_BLOCK),
        new CondenseRule(Items.NETHERITE_INGOT, Items.NETHERITE_BLOCK),
        new CondenseRule(Items.DIAMOND, Items.DIAMOND_BLOCK),
        new CondenseRule(Items.EMERALD, Items.EMERALD_BLOCK),
        new CondenseRule(Items.LAPIS_LAZULI, Items.LAPIS_BLOCK),
        new CondenseRule(Items.REDSTONE, Items.REDSTONE_BLOCK),
        new CondenseRule(Items.COAL, Items.COAL_BLOCK),
        new CondenseRule(Items.QUARTZ, Items.QUARTZ_BLOCK),
        new CondenseRule(Items.AMETHYST_SHARD, Items.AMETHYST_BLOCK),
        new CondenseRule(Items.RAW_IRON, Items.RAW_IRON_BLOCK),
        new CondenseRule(Items.RAW_GOLD, Items.RAW_GOLD_BLOCK),
        new CondenseRule(Items.RAW_COPPER, Items.RAW_COPPER_BLOCK)
    );

    private final Predicate<CommandSourceStack> admin;
    private final Path customTextDirectory;
    private final PlayerPreferenceStore preferences;
    private final PowerToolStore powerTools;
    private final UnlimitedStore unlimited;

    public PlayerUtilityCommands(EssentialsConfig config, Predicate<CommandSourceStack> admin) {
        this.admin = admin;
        this.customTextDirectory = config.configDirectory().resolve("text").resolve("custom");
        this.preferences = new PlayerPreferenceStore(config.configDirectory());
        this.powerTools = new PowerToolStore(config.configDirectory());
        this.unlimited = new UnlimitedStore(config.configDirectory());
    }

    public void registerLifecycle() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> applyPersonalPreferences(handler.player));
        UseItemCallback.EVENT.register(this::onUseItem);
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> onUseItem(player, level, hand));
        ServerTickEvents.END_SERVER_TICK.register(server -> refillUnlimitedItems(server.getPlayerList().getPlayers()));
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        registerAll(dispatcher, this::invSeeCommand, "invsee", "einvsee");
        registerAll(dispatcher, this::expCommand, "exp", "eexp", "xp", "experience", "eexperience");
        registerAll(dispatcher, this::ptimeCommand, "ptime", "eptime", "playertime", "eplayertime");
        registerAll(dispatcher, this::pweatherCommand, "pweather", "epweather", "playerweather", "eplayerweather");
        registerAll(dispatcher, name -> condenseCommand(name, registryAccess), "condense", "econdense", "compact", "ecompact", "blocks", "eblocks", "toblocks", "etoblocks");
        registerAll(dispatcher, name -> unlimitedCommand(name, registryAccess), "unlimited", "eunlimited", "ul", "unl", "eul", "eunl");
        registerAll(dispatcher, this::powerToolCommand, "powertool", "epowertool", "pt", "ept");
        registerAll(dispatcher, this::powerToolListCommand, "powertoollist", "epowertoollist", "ptlist", "eptlist");
        registerAll(dispatcher, this::powerToolToggleCommand, "powertooltoggle", "epowertooltoggle", "ptt", "eptt", "pttoggle", "epttoggle");
        registerAll(dispatcher, this::backupCommand, "backup", "ebackup");
        registerAll(dispatcher, this::customTextCommand, "customtext", "ecustomtext", "ctext", "ectext");
        if (dispatcher.getRoot().getChild("help") == null) {
            dispatcher.register(helpCommand("help", dispatcher));
        }
        registerAll(dispatcher, name -> helpCommand(name, dispatcher), "ehelp", "esshelp", "essentialshelp");
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

    private LiteralArgumentBuilder<CommandSourceStack> invSeeCommand(String name) {
        return Commands.literal(name)
            .requires(admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> invSee(context, getString(context, TARGET_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> expCommand(String name) {
        return Commands.literal(name)
            .requires(admin)
            .executes(context -> expShow(context, context.getSource().getPlayerOrException()))
            .then(Commands.literal("show")
                .executes(context -> expShow(context, context.getSource().getPlayerOrException()))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestPlayers)
                    .executes(context -> expShow(context, resolveTargetOrNull(context)))))
            .then(Commands.literal("reset")
                .executes(context -> expSet(context, context.getSource().getPlayerOrException(), 0))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestPlayers)
                    .executes(context -> expSet(context, resolveTargetOrNull(context), 0))))
            .then(Commands.literal("set")
                .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer(0))
                    .executes(context -> expSet(context, context.getSource().getPlayerOrException(), getInteger(context, AMOUNT_ARGUMENT)))
                    .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                        .suggests(this::suggestPlayers)
                        .executes(context -> expSet(context, resolveTargetOrNull(context), getInteger(context, AMOUNT_ARGUMENT)))))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestPlayers)
                    .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer(0))
                        .executes(context -> expSet(context, resolveTargetOrNull(context), getInteger(context, AMOUNT_ARGUMENT))))))
            .then(Commands.literal("give")
                .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer())
                    .executes(context -> expGive(context, context.getSource().getPlayerOrException(), getInteger(context, AMOUNT_ARGUMENT)))
                    .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                        .suggests(this::suggestPlayers)
                        .executes(context -> expGive(context, resolveTargetOrNull(context), getInteger(context, AMOUNT_ARGUMENT)))))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestPlayers)
                    .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer())
                        .executes(context -> expGive(context, resolveTargetOrNull(context), getInteger(context, AMOUNT_ARGUMENT))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> ptimeCommand(String name) {
        return Commands.literal(name)
            .executes(this::showPtimeDefault)
            .then(personalTimeListBranch("get"))
            .then(personalTimeListBranch("list"))
            .then(personalTimeListBranch("show"))
            .then(personalTimeListBranch("display"))
            .then(Commands.literal("reset")
                .executes(context -> resetPtime(context, context.getSource().getPlayerOrException()))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .requires(admin)
                    .suggests(this::suggestPlayersAndAll)
                    .executes(context -> ptime(context, "reset", getString(context, TARGET_ARGUMENT)))))
            .then(Commands.argument(TIME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestTimesAndReset)
                .executes(context -> ptime(context, getString(context, TIME_ARGUMENT), context.getSource().getPlayerOrException()))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .requires(admin)
                    .suggests(this::suggestPlayersAndAll)
                    .executes(context -> ptime(context, getString(context, TIME_ARGUMENT), getString(context, TARGET_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> pweatherCommand(String name) {
        return Commands.literal(name)
            .executes(this::showPweatherDefault)
            .then(personalWeatherListBranch("get"))
            .then(personalWeatherListBranch("list"))
            .then(personalWeatherListBranch("show"))
            .then(personalWeatherListBranch("display"))
            .then(Commands.literal("reset")
                .executes(context -> resetPweather(context, context.getSource().getPlayerOrException()))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .requires(admin)
                    .suggests(this::suggestPlayersAndAll)
                    .executes(context -> pweather(context, "reset", getString(context, TARGET_ARGUMENT)))))
            .then(Commands.argument(WEATHER_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestPersonalWeather)
                .executes(context -> pweather(context, getString(context, WEATHER_ARGUMENT), context.getSource().getPlayerOrException()))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .requires(admin)
                    .suggests(this::suggestPlayersAndAll)
                    .executes(context -> pweather(context, getString(context, WEATHER_ARGUMENT), getString(context, TARGET_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> condenseCommand(String name, CommandBuildContext registryAccess) {
        return Commands.literal(name)
            .executes(context -> condense(context, null))
            .then(Commands.argument(ITEM_ARGUMENT, ItemArgument.item(registryAccess))
                .executes(context -> condense(context, ItemArgument.getItem(context, ITEM_ARGUMENT).createItemStack(1))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> unlimitedCommand(String name, CommandBuildContext registryAccess) {
        return Commands.literal(name)
            .requires(admin)
            .executes(this::toggleHeldUnlimited)
            .then(Commands.literal("list")
                .executes(context -> listUnlimited(context, context.getSource().getPlayerOrException()))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestPlayers)
                    .executes(context -> listUnlimited(context, resolveTargetOrNull(context)))))
            .then(Commands.literal("clear")
                .executes(context -> clearUnlimited(context, context.getSource().getPlayerOrException()))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestPlayers)
                    .executes(context -> clearUnlimited(context, resolveTargetOrNull(context)))))
            .then(Commands.argument(ITEM_ARGUMENT, ItemArgument.item(registryAccess))
                .executes(context -> toggleUnlimited(context, context.getSource().getPlayerOrException(), ItemArgument.getItem(context, ITEM_ARGUMENT).createItemStack(1)))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestPlayers)
                    .executes(context -> toggleUnlimited(context, resolveTargetOrNull(context), ItemArgument.getItem(context, ITEM_ARGUMENT).createItemStack(1)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> personalTimeListBranch(String name) {
        return Commands.literal(name)
            .executes(this::showPtimeDefault)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .requires(admin)
                .suggests(this::suggestPlayersAndAll)
                .executes(context -> showPtime(context, getString(context, TARGET_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> personalWeatherListBranch(String name) {
        return Commands.literal(name)
            .executes(this::showPweatherDefault)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .requires(admin)
                .suggests(this::suggestPlayersAndAll)
                .executes(context -> showPweather(context, getString(context, TARGET_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> powerToolCommand(String name) {
        return Commands.literal(name)
            .requires(admin)
            .executes(this::showPowerToolUsage)
            .then(Commands.literal("clear").executes(this::clearPowerTool))
            .then(Commands.literal("none").executes(this::clearPowerTool))
            .then(Commands.argument(COMMAND_ARGUMENT, StringArgumentType.greedyString())
                .executes(context -> setPowerTool(context, getString(context, COMMAND_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> powerToolListCommand(String name) {
        return Commands.literal(name)
            .requires(admin)
            .executes(this::listPowerTools);
    }

    private LiteralArgumentBuilder<CommandSourceStack> powerToolToggleCommand(String name) {
        return Commands.literal(name)
            .requires(admin)
            .executes(context -> togglePowerTools(context, context.getSource().getPlayerOrException(), null))
            .then(Commands.argument(TEXT_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestToggleStatesAndPlayers)
                .executes(context -> powerToolToggleTargetOrState(context, getString(context, TEXT_ARGUMENT), null))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestPlayers)
                    .executes(context -> powerToolToggleTargetOrState(
                        context,
                        getString(context, TEXT_ARGUMENT),
                        getString(context, TARGET_ARGUMENT)
                    ))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> backupCommand(String name) {
        return Commands.literal(name)
            .requires(admin)
            .executes(this::backup);
    }

    private LiteralArgumentBuilder<CommandSourceStack> customTextCommand(String name) {
        return Commands.literal(name)
            .executes(this::listCustomText)
            .then(Commands.argument(TEXT_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestCustomText)
                .executes(context -> showCustomText(context, getString(context, TEXT_ARGUMENT), 1))
                .then(Commands.argument(PAGE_ARGUMENT, IntegerArgumentType.integer(1))
                    .executes(context -> showCustomText(context, getString(context, TEXT_ARGUMENT), getInteger(context, PAGE_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> helpCommand(String name, CommandDispatcher<CommandSourceStack> dispatcher) {
        return Commands.literal(name)
            .executes(context -> showHelp(context, dispatcher, null, 1))
            .then(Commands.argument(TEXT_ARGUMENT, StringArgumentType.string())
                .executes(context -> helpQueryOrPage(context, dispatcher, getString(context, TEXT_ARGUMENT)))
                .then(Commands.argument(PAGE_ARGUMENT, IntegerArgumentType.integer(1))
                    .executes(context -> showHelp(context, dispatcher, getString(context, TEXT_ARGUMENT), getInteger(context, PAGE_ARGUMENT)))));
    }

    private int invSee(CommandContext<CommandSourceStack> context, String rawTarget) throws CommandSyntaxException {
        ServerPlayer viewer = context.getSource().getPlayerOrException();
        ServerPlayer target = resolveTarget(context.getSource().getServer(), rawTarget);
        if (target == null) {
            context.getSource().sendSystemMessage(Messages.error("Player not found: " + rawTarget));
            return 0;
        }
        if (target == viewer) {
            context.getSource().sendSystemMessage(Messages.error("Use your own inventory screen for yourself."));
            return 0;
        }
        MenuProvider provider = new SimpleMenuProvider(
            (syncId, inventory, player) -> ChestMenu.sixRows(syncId, inventory, new ViewedInventory(target)),
            Component.literal(displayName(target) + " Inventory")
        );
        if (viewer.openMenu(provider).isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Could not open target inventory."));
            return 0;
        }
        return 1;
    }

    private int expShow(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        if (target == null) {
            return 0;
        }
        context.getSource().sendSystemMessage(Messages.line(
            "Experience: " + displayName(target),
            target.totalExperience + " points, level " + target.experienceLevel + ", progress " + Math.round(target.experienceProgress * 100.0F) + "%"
        ));
        return 1;
    }

    private int expSet(CommandContext<CommandSourceStack> context, ServerPlayer target, int amount) {
        if (target == null) {
            return 0;
        }
        setTotalExperience(target, amount);
        context.getSource().sendSystemMessage(Messages.success("Experience set: " + displayName(target) + " -> " + amount + " points"));
        return 1;
    }

    private int expGive(CommandContext<CommandSourceStack> context, ServerPlayer target, int amount) {
        if (target == null) {
            return 0;
        }
        target.giveExperiencePoints(amount);
        syncExperience(target);
        context.getSource().sendSystemMessage(Messages.success("Experience changed: " + displayName(target) + " -> " + amount + " points"));
        return 1;
    }

    private int showPtimeDefault(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayer();
        if (player != null) {
            return showPtime(context, player);
        }
        return showPtime(context, "*");
    }

    private int showPtime(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        if (target == null) {
            return 0;
        }
        Optional<Long> preference = preferences.personalTime(target.getUUID());
        context.getSource().sendSystemMessage(Messages.line(
            "Player time: " + displayName(target),
            preference.map(PlayerUtilityCommands::formatTime).orElse("server")
        ));
        return 1;
    }

    private int showPtime(CommandContext<CommandSourceStack> context, String selector) {
        List<ServerPlayer> targets = resolveTargets(context, selector);
        if (targets.isEmpty()) {
            return 0;
        }
        if (targets.size() > 1) {
            context.getSource().sendSystemMessage(Messages.header("Player times"));
        }
        targets.forEach(target -> showPtime(context, target));
        return targets.size();
    }

    private int ptime(CommandContext<CommandSourceStack> context, String rawTime, ServerPlayer target) {
        if (target == null) {
            return 0;
        }
        if (isReset(rawTime)) {
            return resetPtime(context, target);
        }
        Long ticks = parseTimeTicks(rawTime);
        if (ticks == null) {
            context.getSource().sendSystemMessage(Messages.error("Invalid player time: " + rawTime));
            return 0;
        }
        if (!sendPersonalTime(target, ticks)) {
            context.getSource().sendSystemMessage(Messages.error("This dimension has no default clock: " + target.level().dimension().identifier()));
            return 0;
        }
        preferences.setPersonalTime(target.getUUID(), ticks);
        context.getSource().sendSystemMessage(Messages.success("Player time set: " + displayName(target) + " -> " + formatTime(ticks)));
        return 1;
    }

    private int ptime(CommandContext<CommandSourceStack> context, String rawTime, String selector) {
        List<ServerPlayer> targets = resolveTargets(context, selector);
        int changed = 0;
        for (ServerPlayer target : targets) {
            changed += ptime(context, rawTime, target) > 0 ? 1 : 0;
        }
        return changed;
    }

    private int resetPtime(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        if (target == null) {
            return 0;
        }
        preferences.clearPersonalTime(target.getUUID());
        target.connection.send(target.level().getServer().clockManager().createFullSyncPacket());
        context.getSource().sendSystemMessage(Messages.success("Player time reset: " + displayName(target)));
        return 1;
    }

    private int showPweatherDefault(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayer();
        if (player != null) {
            return showPweather(context, player);
        }
        return showPweather(context, "*");
    }

    private int showPweather(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        if (target == null) {
            return 0;
        }
        context.getSource().sendSystemMessage(Messages.line(
            "Player weather: " + displayName(target),
            preferences.personalWeather(target.getUUID()).orElse("server")
        ));
        return 1;
    }

    private int showPweather(CommandContext<CommandSourceStack> context, String selector) {
        List<ServerPlayer> targets = resolveTargets(context, selector);
        if (targets.isEmpty()) {
            return 0;
        }
        if (targets.size() > 1) {
            context.getSource().sendSystemMessage(Messages.header("Player weather"));
        }
        targets.forEach(target -> showPweather(context, target));
        return targets.size();
    }

    private int pweather(CommandContext<CommandSourceStack> context, String rawWeather, ServerPlayer target) {
        if (target == null) {
            return 0;
        }
        String weather = rawWeather.toLowerCase(Locale.ROOT);
        if (isReset(weather)) {
            return resetPweather(context, target);
        }
        if (!weather.equals("sun") && !weather.equals("clear") && !weather.equals("storm") && !weather.equals("rain") && !weather.equals("thunder")) {
            context.getSource().sendSystemMessage(Messages.error("Invalid player weather: " + rawWeather));
            return 0;
        }
        String stored = weather.equals("storm") || weather.equals("rain") || weather.equals("thunder") ? "storm" : "sun";
        sendPersonalWeather(target, stored);
        preferences.setPersonalWeather(target.getUUID(), stored);
        context.getSource().sendSystemMessage(Messages.success("Player weather set: " + displayName(target) + " -> " + stored));
        return 1;
    }

    private int pweather(CommandContext<CommandSourceStack> context, String rawWeather, String selector) {
        List<ServerPlayer> targets = resolveTargets(context, selector);
        int changed = 0;
        for (ServerPlayer target : targets) {
            changed += pweather(context, rawWeather, target) > 0 ? 1 : 0;
        }
        return changed;
    }

    private int resetPweather(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        if (target == null) {
            return 0;
        }
        preferences.clearPersonalWeather(target.getUUID());
        sendServerWeather(target);
        context.getSource().sendSystemMessage(Messages.success("Player weather reset: " + displayName(target)));
        return 1;
    }

    private int condense(CommandContext<CommandSourceStack> context, ItemStack requestedItem) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int converted = 0;
        for (CondenseRule rule : CONDENSE_RULES) {
            if (requestedItem != null && requestedItem.getItem() != rule.source()) {
                continue;
            }
            converted += applyCondenseRule(player, rule);
        }
        if (converted == 0) {
            String suffix = requestedItem == null ? "" : " for " + itemId(requestedItem);
            context.getSource().sendSystemMessage(Messages.error("No condensable item stacks found" + suffix + "."));
            return 0;
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Condensed " + converted + " stacks/items."));
        return converted;
    }

    private int toggleHeldUnlimited(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Hold an item to toggle unlimited mode."));
            return 0;
        }
        return toggleUnlimited(context, player, held);
    }

    private int toggleUnlimited(CommandContext<CommandSourceStack> context, ServerPlayer player, ItemStack stack) {
        if (player == null) {
            return 0;
        }
        String itemId = itemId(stack);
        boolean enabled = unlimited.toggle(player.getUUID(), itemId);
        context.getSource().sendSystemMessage(Messages.success((enabled ? "Unlimited enabled: " : "Unlimited disabled: ") + itemId + " for " + displayName(player)));
        refillUnlimitedItems(List.of(player));
        return 1;
    }

    private int listUnlimited(CommandContext<CommandSourceStack> context, ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        Set<String> items = unlimited.items(player.getUUID());
        context.getSource().sendSystemMessage(Messages.line("Unlimited items: " + displayName(player), items.isEmpty() ? "none" : String.join(", ", items)));
        return items.size();
    }

    private int clearUnlimited(CommandContext<CommandSourceStack> context, ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        unlimited.clear(player.getUUID());
        context.getSource().sendSystemMessage(Messages.success("Unlimited items cleared: " + displayName(player)));
        return 1;
    }

    private int showPowerToolUsage(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSystemMessage(Messages.usage("/powertool <command>, /powertool clear, /powertoollist, /powertooltoggle [on|off]"));
        return 0;
    }

    private int setPowerTool(CommandContext<CommandSourceStack> context, String rawCommand) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Hold an item to bind a powertool command."));
            return 0;
        }
        String command = rawCommand.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isBlank()) {
            return clearPowerTool(context);
        }
        String itemId = itemId(held);
        powerTools.set(player.getUUID(), itemId, command);
        context.getSource().sendSystemMessage(Messages.success("Powertool set: " + itemId + " -> /" + command));
        return 1;
    }

    private int clearPowerTool(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Hold an item to clear its powertool command."));
            return 0;
        }
        String itemId = itemId(held);
        if (powerTools.clear(player.getUUID(), itemId)) {
            context.getSource().sendSystemMessage(Messages.success("Powertool cleared: " + itemId));
            return 1;
        }
        context.getSource().sendSystemMessage(Messages.error("No powertool command is bound to: " + itemId));
        return 0;
    }

    private int listPowerTools(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Map<String, String> mappings = powerTools.mappings(player.getUUID());
        if (mappings.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.line("Powertools", "none"));
            return 0;
        }
        context.getSource().sendSystemMessage(Messages.header("Powertools"));
        mappings.forEach((item, command) -> context.getSource().sendSystemMessage(Messages.line(item, "/" + command)));
        return mappings.size();
    }

    private int powerToolToggleTargetOrState(CommandContext<CommandSourceStack> context, String first, String second) throws CommandSyntaxException {
        Boolean firstState = parseToggle(first);
        if (second == null) {
            if (firstState != null) {
                return togglePowerTools(context, context.getSource().getPlayerOrException(), firstState);
            }
            ServerPlayer target = resolveTarget(context.getSource().getServer(), first);
            return target == null ? 0 : togglePowerTools(context, target, null);
        }

        ServerPlayer target;
        Boolean state = parseToggle(second);
        if (firstState != null) {
            state = firstState;
            target = resolveTarget(context.getSource().getServer(), second);
        } else {
            target = resolveTarget(context.getSource().getServer(), first);
        }
        return target == null ? 0 : togglePowerTools(context, target, state);
    }

    private int togglePowerTools(CommandContext<CommandSourceStack> context, ServerPlayer target, Boolean desired) {
        boolean enabled = desired == null ? powerTools.toggleEnabled(target.getUUID()) : powerTools.setEnabled(target.getUUID(), desired);
        context.getSource().sendSystemMessage(Messages.success("Powertools " + (enabled ? "enabled" : "disabled") + ": " + displayName(target)));
        return 1;
    }

    private int backup(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        context.getSource().sendSystemMessage(Messages.success("Saving players and worlds..."));
        server.getPlayerList().saveAll();
        boolean ok = server.saveEverything(false, true, true);
        context.getSource().sendSystemMessage(ok ? Messages.success("World save completed.") : Messages.error("World save reported failure."));
        return ok ? 1 : 0;
    }

    private int listCustomText(CommandContext<CommandSourceStack> context) {
        List<String> texts = customTextNames();
        if (texts.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("No custom text files configured in config/mod_essentials/text/custom."));
            return 0;
        }
        context.getSource().sendSystemMessage(Messages.line("Custom text", String.join(", ", texts)));
        return texts.size();
    }

    private int showCustomText(CommandContext<CommandSourceStack> context, String rawName, int page) {
        String name = safeName(rawName);
        if (name.isBlank()) {
            context.getSource().sendSystemMessage(Messages.error("Invalid custom text name: " + rawName));
            return 0;
        }
        return showTextFile(context, "Custom Text: " + name, customTextDirectory.resolve(name + ".txt"), page);
    }

    private int helpQueryOrPage(CommandContext<CommandSourceStack> context, CommandDispatcher<CommandSourceStack> dispatcher, String queryOrPage) {
        Integer page = parsePositiveInt(queryOrPage);
        if (page != null) {
            return showHelp(context, dispatcher, null, page);
        }
        return showHelp(context, dispatcher, queryOrPage, 1);
    }

    private int showHelp(
        CommandContext<CommandSourceStack> context,
        CommandDispatcher<CommandSourceStack> dispatcher,
        String rawQuery,
        int page
    ) {
        String query = rawQuery == null ? "" : rawQuery.toLowerCase(Locale.ROOT);
        List<String> commands = dispatcher.getRoot().getChildren().stream()
            .filter(node -> node.canUse(context.getSource()))
            .map(CommandNode::getName)
            .filter(name -> query.isBlank() || name.toLowerCase(Locale.ROOT).contains(query))
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();

        if (commands.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("No commands match: " + rawQuery));
            return 0;
        }

        int totalPages = Math.max(1, (commands.size() + HELP_PAGE_SIZE - 1) / HELP_PAGE_SIZE);
        if (page > totalPages) {
            context.getSource().sendSystemMessage(Messages.error("Help page " + page + " is outside " + totalPages + " pages."));
            return 0;
        }

        context.getSource().sendSystemMessage(Messages.header("Help " + page + "/" + totalPages));
        int start = (page - 1) * HELP_PAGE_SIZE;
        int end = Math.min(commands.size(), start + HELP_PAGE_SIZE);
        for (int index = start; index < end; index++) {
            String command = commands.get(index);
            context.getSource().sendSystemMessage(Component.literal("/" + command).withStyle(ChatFormatting.WHITE));
        }
        return end - start;
    }

    private int showTextFile(CommandContext<CommandSourceStack> context, String title, Path file, int requestedPage) {
        if (!Files.isRegularFile(file)) {
            context.getSource().sendSystemMessage(Messages.error("Static text is not configured: " + file));
            return 0;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int totalPages = Math.max(1, (lines.size() + TEXT_PAGE_SIZE - 1) / TEXT_PAGE_SIZE);
            if (requestedPage > totalPages) {
                context.getSource().sendSystemMessage(Messages.error("Page " + requestedPage + " is outside " + totalPages + " pages."));
                return 0;
            }
            context.getSource().sendSystemMessage(Messages.header(title + " " + requestedPage + "/" + totalPages));
            int start = (requestedPage - 1) * TEXT_PAGE_SIZE;
            int end = Math.min(lines.size(), start + TEXT_PAGE_SIZE);
            for (int index = start; index < end; index++) {
                context.getSource().sendSystemMessage(Component.literal(lines.get(index)).withStyle(ChatFormatting.WHITE));
            }
            return end - start;
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to read Essentials custom text file {}", file, exception);
            context.getSource().sendSystemMessage(Messages.error("Unable to read custom text: " + file.getFileName()));
            return 0;
        }
    }

    private InteractionResult onUseItem(Player player, net.minecraft.world.level.Level level, InteractionHand hand) {
        if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        String command = powerTools.command(serverPlayer.getUUID(), itemId(serverPlayer.getMainHandItem())).orElse(null);
        if (command == null || command.isBlank() || !powerTools.isEnabled(serverPlayer.getUUID())) {
            return InteractionResult.PASS;
        }
        serverPlayer.level().getServer().getCommands().performPrefixedCommand(serverPlayer.createCommandSourceStack(), command);
        return InteractionResult.SUCCESS_SERVER;
    }

    private void applyPersonalPreferences(ServerPlayer player) {
        preferences.personalTime(player.getUUID()).ifPresent(ticks -> sendPersonalTime(player, ticks));
        preferences.personalWeather(player.getUUID()).ifPresent(weather -> sendPersonalWeather(player, weather));
    }

    private void refillUnlimitedItems(List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            Set<String> items = unlimited.items(player.getUUID());
            if (items.isEmpty()) {
                continue;
            }
            boolean changed = refillUnlimitedItem(player.getItemInHand(InteractionHand.MAIN_HAND), items);
            changed |= refillUnlimitedItem(player.getItemInHand(InteractionHand.OFF_HAND), items);
            if (changed) {
                player.getInventory().setChanged();
                player.containerMenu.broadcastChanges();
            }
        }
    }

    private boolean refillUnlimitedItem(ItemStack stack, Set<String> items) {
        if (stack.isEmpty() || stack.getMaxStackSize() <= 1 || !items.contains(itemId(stack))) {
            return false;
        }
        if (stack.getCount() >= stack.getMaxStackSize()) {
            return false;
        }
        stack.setCount(stack.getMaxStackSize());
        return true;
    }

    private int applyCondenseRule(ServerPlayer player, CondenseRule rule) {
        Inventory inventory = player.getInventory();
        int available = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isPlainItem(stack, rule.source())) {
                available += stack.getCount();
            }
        }

        int outputCount = available / 9;
        if (outputCount <= 0) {
            return 0;
        }

        int toRemove = outputCount * 9;
        for (int slot = 0; slot < inventory.getContainerSize() && toRemove > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isPlainItem(stack, rule.source())) {
                continue;
            }
            int removed = Math.min(toRemove, stack.getCount());
            stack.shrink(removed);
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
            toRemove -= removed;
        }

        int remaining = outputCount;
        int maxStack = rule.target().getDefaultMaxStackSize();
        while (remaining > 0) {
            int count = Math.min(maxStack, remaining);
            ItemStack output = new ItemStack(rule.target(), count);
            inventory.add(output);
            if (!output.isEmpty()) {
                player.drop(output, false, false);
            }
            remaining -= count;
        }
        return outputCount;
    }

    private static boolean isPlainItem(ItemStack stack, Item item) {
        return !stack.isEmpty() && stack.getItem() == item && stack.getComponentsPatch().isEmpty();
    }

    private static void setTotalExperience(ServerPlayer target, int amount) {
        target.totalExperience = 0;
        target.experienceLevel = 0;
        target.experienceProgress = 0.0F;
        target.giveExperiencePoints(Math.max(0, amount));
        syncExperience(target);
    }

    private static void syncExperience(ServerPlayer target) {
        target.connection.send(new ClientboundSetExperiencePacket(
            target.experienceProgress,
            target.totalExperience,
            target.experienceLevel
        ));
    }

    private static boolean sendPersonalTime(ServerPlayer player, long ticks) {
        ServerLevel level = player.level();
        Holder<WorldClock> clock = level.dimensionType().defaultClock().orElse(null);
        if (clock == null) {
            return false;
        }
        long current = level.clockManager().getTotalTicks(clock);
        long base = current - Math.floorMod(current, 24000L);
        long total = base + Math.floorMod(ticks, 24000L);
        player.connection.send(new ClientboundSetTimePacket(
            level.getGameTime(),
            Map.of(clock, new ClockNetworkState(total, 0.0F, 0.0F))
        ));
        return true;
    }

    private static void sendPersonalWeather(ServerPlayer player, String weather) {
        boolean storm = weather.equalsIgnoreCase("storm") || weather.equalsIgnoreCase("rain");
        player.connection.send(new ClientboundGameEventPacket(
            storm ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING,
            0.0F
        ));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, storm ? 1.0F : 0.0F));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0.0F));
    }

    private static void sendServerWeather(ServerPlayer player) {
        ServerLevel level = player.level();
        boolean raining = level.isRaining();
        player.connection.send(new ClientboundGameEventPacket(
            raining ? ClientboundGameEventPacket.START_RAINING : ClientboundGameEventPacket.STOP_RAINING,
            0.0F
        ));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, raining ? level.getRainLevel(1.0F) : 0.0F));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, raining ? level.getThunderLevel(1.0F) : 0.0F));
    }

    private CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(playerSuggestions(context.getSource().getServer()), builder);
    }

    private CompletableFuture<Suggestions> suggestTimesAndReset(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(List.of("get", "list", "reset", "sunrise", "day", "morning", "noon", "afternoon", "sunset", "night", "midnight", "17:30", "4pm", "4000ticks"), builder);
    }

    private CompletableFuture<Suggestions> suggestPersonalWeather(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(List.of("get", "list", "sun", "storm", "thunder", "rain", "clear", "reset"), builder);
    }

    private CompletableFuture<Suggestions> suggestToggleStatesAndPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> suggestions = new LinkedHashSet<>(List.of("on", "off", "true", "false"));
        suggestions.addAll(playerSuggestions(context.getSource().getServer()));
        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private CompletableFuture<Suggestions> suggestCustomText(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(customTextNames(), builder);
    }

    private CompletableFuture<Suggestions> suggestPlayersAndAll(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> suggestions = new LinkedHashSet<>(List.of("*", "**"));
        suggestions.addAll(playerSuggestions(context.getSource().getServer()));
        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private List<String> customTextNames() {
        if (!Files.isDirectory(customTextDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(customTextDirectory)) {
            return files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".txt"))
                .map(name -> name.substring(0, name.length() - 4))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to list Essentials custom text files", exception);
            return List.of();
        }
    }

    private ServerPlayer resolveTargetOrNull(CommandContext<CommandSourceStack> context) {
        return resolveTarget(context.getSource().getServer(), getString(context, TARGET_ARGUMENT));
    }

    private List<ServerPlayer> resolveTargets(CommandContext<CommandSourceStack> context, String rawSelector) {
        String selector = stripWrappingQuotes(rawSelector).trim();
        if (selector.equals("*") || selector.equals("**")) {
            return context.getSource().getServer().getPlayerList().getPlayers();
        }
        ServerPlayer target = resolveTarget(context.getSource().getServer(), selector);
        if (target == null) {
            context.getSource().sendSystemMessage(Messages.error("Player not found: " + rawSelector));
            return List.of();
        }
        return List.of(target);
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

    private static Long parseTimeTicks(String rawValue) {
        String value = rawValue.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9:]", "");
        return switch (value) {
            case "sunrise", "dawn" -> 23000L;
            case "daystart", "day" -> 0L;
            case "morning" -> 1000L;
            case "midday", "noon" -> 6000L;
            case "afternoon" -> 9000L;
            case "sunset", "dusk", "sundown", "nightfall" -> 12000L;
            case "nightstart", "night" -> 14000L;
            case "midnight" -> 18000L;
            default -> parseNumericTime(value);
        };
    }

    private static Long parseNumericTime(String value) {
        try {
            if (value.matches("^[0-9]+ti?c?k?s?$")) {
                return Long.parseLong(value.replaceAll("[^0-9]", "")) % 24000L;
            }
            if (value.matches("^[0-9]+$")) {
                return Long.parseLong(value) % 24000L;
            }
            if (value.matches("^[0-9]{1,2}(am|pm)$")) {
                int hour = Integer.parseInt(value.substring(0, value.length() - 2));
                if (value.endsWith("pm") && hour < 12) {
                    hour += 12;
                } else if (value.endsWith("am") && hour == 12) {
                    hour = 0;
                }
                return hoursMinutesToTicks(hour, 0);
            }
            if (value.matches("^[0-9]{2}:?[0-9]{2}$")) {
                String digits = value.replace(":", "");
                return hoursMinutesToTicks(Integer.parseInt(digits.substring(0, 2)), Integer.parseInt(digits.substring(2, 4)));
            }
        } catch (NumberFormatException exception) {
            return null;
        }
        return null;
    }

    private static long hoursMinutesToTicks(int hours, int minutes) {
        int normalizedHours = Math.floorMod(hours - 6, 24);
        long minutesSinceSix = normalizedHours * 60L + Math.max(0, Math.min(59, minutes));
        return Math.floorMod(minutesSinceSix * 1000L / 60L, 24000L);
    }

    private static String formatTime(long ticks) {
        long normalized = Math.floorMod(ticks, 24000L);
        long minutesSinceSix = normalized * 60L / 1000L;
        long totalMinutes = (minutesSinceSix + 6L * 60L) % (24L * 60L);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return String.format(Locale.ROOT, "%02d:%02d (%d ticks)", hours, minutes, normalized);
    }

    private static boolean isReset(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "reset", "clear", "server", "normal" -> true;
            default -> false;
        };
    }

    private static Boolean parseToggle(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "enable", "enabled" -> true;
            case "off", "false", "no", "disable", "disabled" -> false;
            default -> null;
        };
    }

    private static Integer parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
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

    private static String itemId(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "minecraft:air" : id.toString();
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

    private static String displayName(ServerPlayer player) {
        return player.getDisplayName().getString();
    }

    private record CondenseRule(Item source, Item target) {
    }

    private static final class ViewedInventory implements Container {
        private final ServerPlayer target;

        private ViewedInventory(ServerPlayer target) {
            this.target = target;
        }

        @Override
        public int getContainerSize() {
            return INVENTORY_VIEW_SIZE;
        }

        @Override
        public boolean isEmpty() {
            return target.getInventory().isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return isRealSlot(slot) ? target.getInventory().getItem(slot) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return isRealSlot(slot) ? target.getInventory().removeItem(slot, amount) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return isRealSlot(slot) ? target.getInventory().removeItemNoUpdate(slot) : ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (isRealSlot(slot)) {
                target.getInventory().setItem(slot, stack);
            }
        }

        @Override
        public void setChanged() {
            target.getInventory().setChanged();
            target.containerMenu.broadcastChanges();
        }

        @Override
        public boolean stillValid(Player player) {
            return !target.hasDisconnected();
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return isRealSlot(slot);
        }

        @Override
        public boolean canTakeItem(Container targetContainer, int slot, ItemStack stack) {
            return isRealSlot(slot);
        }

        @Override
        public void clearContent() {
            target.getInventory().clearContent();
        }

        private boolean isRealSlot(int slot) {
            return slot >= 0 && slot < target.getInventory().getContainerSize();
        }
    }

    private static final class PlayerPreferenceStore {
        private final Path file;
        private final Properties properties;

        private PlayerPreferenceStore(Path configDirectory) {
            this.file = configDirectory.resolve("player-preferences.properties");
            this.properties = loadProperties(file);
        }

        private synchronized Optional<Long> personalTime(UUID playerUuid) {
            String value = properties.getProperty("ptime." + playerUuid);
            if (value == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(Long.parseLong(value));
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
        }

        private synchronized void setPersonalTime(UUID playerUuid, long ticks) {
            properties.setProperty("ptime." + playerUuid, Long.toString(Math.floorMod(ticks, 24000L)));
            save();
        }

        private synchronized void clearPersonalTime(UUID playerUuid) {
            properties.remove("ptime." + playerUuid);
            save();
        }

        private synchronized Optional<String> personalWeather(UUID playerUuid) {
            return Optional.ofNullable(properties.getProperty("pweather." + playerUuid));
        }

        private synchronized void setPersonalWeather(UUID playerUuid, String weather) {
            properties.setProperty("pweather." + playerUuid, weather);
            save();
        }

        private synchronized void clearPersonalWeather(UUID playerUuid) {
            properties.remove("pweather." + playerUuid);
            save();
        }

        private void save() {
            saveProperties(file, properties, "Vantablack Essentials player preferences");
        }
    }

    private static final class PowerToolStore {
        private final Path file;
        private final Properties properties;

        private PowerToolStore(Path configDirectory) {
            this.file = configDirectory.resolve("powertools.properties");
            this.properties = loadProperties(file);
        }

        private synchronized Optional<String> command(UUID playerUuid, String itemId) {
            return Optional.ofNullable(properties.getProperty(commandKey(playerUuid, itemId)));
        }

        private synchronized void set(UUID playerUuid, String itemId, String command) {
            properties.setProperty(commandKey(playerUuid, itemId), command);
            save();
        }

        private synchronized boolean clear(UUID playerUuid, String itemId) {
            Object removed = properties.remove(commandKey(playerUuid, itemId));
            if (removed != null) {
                save();
                return true;
            }
            return false;
        }

        private synchronized Map<String, String> mappings(UUID playerUuid) {
            String prefix = "command." + playerUuid + ".";
            Map<String, String> mappings = new LinkedHashMap<>();
            properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith(prefix))
                .sorted()
                .forEach(key -> mappings.put(key.substring(prefix.length()), properties.getProperty(key)));
            return mappings;
        }

        private synchronized boolean isEnabled(UUID playerUuid) {
            return Boolean.parseBoolean(properties.getProperty("enabled." + playerUuid, "true"));
        }

        private synchronized boolean toggleEnabled(UUID playerUuid) {
            return setEnabled(playerUuid, !isEnabled(playerUuid));
        }

        private synchronized boolean setEnabled(UUID playerUuid, boolean enabled) {
            properties.setProperty("enabled." + playerUuid, Boolean.toString(enabled));
            save();
            return enabled;
        }

        private void save() {
            saveProperties(file, properties, "Vantablack Essentials powertools");
        }

        private static String commandKey(UUID playerUuid, String itemId) {
            return "command." + playerUuid + "." + itemId;
        }
    }

    private static final class UnlimitedStore {
        private final Path file;
        private final Properties properties;

        private UnlimitedStore(Path configDirectory) {
            this.file = configDirectory.resolve("unlimited.properties");
            this.properties = loadProperties(file);
        }

        private synchronized Set<String> items(UUID playerUuid) {
            String value = properties.getProperty("items." + playerUuid, "");
            Set<String> items = new LinkedHashSet<>();
            for (String item : value.split(",")) {
                String trimmed = item.trim();
                if (!trimmed.isBlank()) {
                    items.add(trimmed);
                }
            }
            return items;
        }

        private synchronized boolean toggle(UUID playerUuid, String itemId) {
            Set<String> items = items(playerUuid);
            boolean enabled;
            if (items.contains(itemId)) {
                items.remove(itemId);
                enabled = false;
            } else {
                items.add(itemId);
                enabled = true;
            }
            setItems(playerUuid, items);
            return enabled;
        }

        private synchronized void clear(UUID playerUuid) {
            properties.remove("items." + playerUuid);
            save();
        }

        private void setItems(UUID playerUuid, Set<String> items) {
            if (items.isEmpty()) {
                properties.remove("items." + playerUuid);
            } else {
                properties.setProperty("items." + playerUuid, String.join(",", items));
            }
            save();
        }

        private void save() {
            saveProperties(file, properties, "Vantablack Essentials unlimited items");
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
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to save Essentials properties: {}", file, exception);
        }
    }
}
