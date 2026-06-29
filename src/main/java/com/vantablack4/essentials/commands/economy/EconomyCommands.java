package com.vantablack4.essentials.commands.economy;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import com.vantablack4.essentials.Messages;
import com.vantablack4.essentials.i18n.EssentialsXMessages;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class EconomyCommands {
    private static final String TARGET_ARGUMENT = "target";
    private static final String AMOUNT_ARGUMENT = "amount";
    private static final String PAGE_ARGUMENT = "page";
    private static final String ITEM_ARGUMENT = "item";
    private static final Pattern MESSAGE_TAG = Pattern.compile("<[^>]+>");
    private static final EssentialsXMessages UPSTREAM_MESSAGES = EssentialsXMessages.loadDefault();

    private EconomyCommands() {
    }

    public static void register(
        CommandDispatcher<CommandSourceStack> dispatcher,
        CommandBuildContext registryAccess,
        Path configDirectory,
        Predicate<CommandSourceStack> admin
    ) {
        EconomySettings settings = EconomySettings.load(configDirectory);
        EconomyService service = new EconomyService(settings, EconomyStore.load(configDirectory));
        registerAll(dispatcher, name -> balanceCommand(name, service, admin), "balance", "bal", "ebal", "ebalance", "money", "emoney");
        registerAll(dispatcher, name -> balanceTopCommand(name, service), "balancetop", "ebalancetop", "baltop", "ebaltop");
        registerAll(dispatcher, name -> payCommand(name, service), "pay", "epay");
        registerAll(dispatcher, name -> payToggleCommand(name, service, admin, forcedPayToggleState(name)), "paytoggle", "epaytoggle", "payoff", "epayoff", "payon", "epayon");
        registerAll(dispatcher, name -> payConfirmToggleCommand(name, service, forcedPayConfirmState(name)), "payconfirmtoggle", "epayconfirmtoggle", "payconfirmoff", "epayconfirmoff", "payconfirmon", "epayconfirmon", "payconfirm", "epayconfirm");
        registerAll(dispatcher, name -> ecoCommand(name, service, admin), "eco", "eeco", "economy", "eeconomy");
        registerAll(dispatcher, name -> worthCommand(name, registryAccess, service), "worth", "eworth", "price", "eprice");
        registerAll(dispatcher, name -> setWorthCommand(name, registryAccess, service, admin), "setworth", "esetworth");
        registerAll(dispatcher, name -> sellCommand(name, registryAccess, service), "sell", "esell");
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

    private static LiteralArgumentBuilder<CommandSourceStack> balanceCommand(
        String name,
        EconomyService service,
        Predicate<CommandSourceStack> admin
    ) {
        return Commands.literal(name)
            .executes(context -> balanceSelf(context, service))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.greedyString())
                .requires(admin)
                .suggests((context, builder) -> suggestAccounts(context, service, builder))
                .executes(context -> balanceTarget(context, service)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> balanceTopCommand(String name, EconomyService service) {
        return Commands.literal(name)
            .executes(context -> balanceTop(context, service, 1))
            .then(Commands.argument(PAGE_ARGUMENT, IntegerArgumentType.integer(1))
                .executes(context -> balanceTop(context, service, getInteger(context, PAGE_ARGUMENT))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> payCommand(String name, EconomyService service) {
        return Commands.literal(name)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(EconomyCommands::suggestOnlinePlayers)
                .then(Commands.argument(AMOUNT_ARGUMENT, StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of(service.settings().minimumPayAmount().toPlainString(), "1", "10", "100").stream(), builder))
                    .executes(context -> pay(context, service, name))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> payToggleCommand(
        String name,
        EconomyService service,
        Predicate<CommandSourceStack> admin,
        Boolean forcedState
    ) {
        return Commands.literal(name)
            .executes(context -> payToggleSelf(context, service, forcedState))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.greedyString())
                .requires(admin)
                .suggests((context, builder) -> suggestAccounts(context, service, builder))
                .executes(context -> payToggleTarget(context, service, forcedState)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> payConfirmToggleCommand(
        String name,
        EconomyService service,
        Boolean forcedState
    ) {
        return Commands.literal(name)
            .executes(context -> payConfirmToggle(context, service, forcedState));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> ecoCommand(
        String name,
        EconomyService service,
        Predicate<CommandSourceStack> admin
    ) {
        return Commands.literal(name)
            .requires(admin)
            .then(ecoAmountBranch("give", service, EconomyService.EcoOperation.GIVE))
            .then(ecoAmountBranch("take", service, EconomyService.EcoOperation.TAKE))
            .then(ecoAmountBranch("set", service, EconomyService.EcoOperation.SET))
            .then(Commands.literal("reset")
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.greedyString())
                    .suggests((context, builder) -> suggestAccounts(context, service, builder))
                    .executes(context -> eco(context, service, EconomyService.EcoOperation.RESET, BigDecimal.ZERO))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> ecoAmountBranch(
        String name,
        EconomyService service,
        EconomyService.EcoOperation operation
    ) {
        return Commands.literal(name)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests((context, builder) -> suggestAccounts(context, service, builder))
                .then(Commands.argument(AMOUNT_ARGUMENT, StringArgumentType.word())
                    .suggests(EconomyCommands::suggestAmounts)
                    .executes(context -> eco(context, service, operation, parseAmount(context, AMOUNT_ARGUMENT, operation == EconomyService.EcoOperation.SET)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> worthCommand(
        String name,
        CommandBuildContext registryAccess,
        EconomyService service
    ) {
        return Commands.literal(name)
            .executes(context -> worthHeld(context, service, null))
            .then(Commands.literal("hand")
                .executes(context -> worthHeld(context, service, null))
                .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer())
                    .executes(context -> worthHeld(context, service, getInteger(context, AMOUNT_ARGUMENT)))))
            .then(Commands.literal("inventory")
                .executes(context -> worthBulk(context, service, BulkMode.ALL, false)))
            .then(Commands.literal("all")
                .executes(context -> worthBulk(context, service, BulkMode.ALL, false)))
            .then(Commands.literal("blocks")
                .executes(context -> worthBulk(context, service, BulkMode.BLOCKS, false))
                .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer())
                    .executes(context -> worthBulk(context, service, BulkMode.BLOCKS, false, getInteger(context, AMOUNT_ARGUMENT)))))
            .then(Commands.argument(ITEM_ARGUMENT, ItemArgument.item(registryAccess))
                .executes(context -> worthItem(context, service, ItemArgument.getItem(context, ITEM_ARGUMENT), null))
                .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer())
                    .executes(context -> worthItem(context, service, ItemArgument.getItem(context, ITEM_ARGUMENT), getInteger(context, AMOUNT_ARGUMENT)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> setWorthCommand(
        String name,
        CommandBuildContext registryAccess,
        EconomyService service,
        Predicate<CommandSourceStack> admin
    ) {
        return Commands.literal(name)
            .requires(admin)
            .then(Commands.argument(AMOUNT_ARGUMENT, StringArgumentType.word())
                .executes(context -> setWorthHeld(context, service, parseAmount(context, AMOUNT_ARGUMENT, true))))
            .then(Commands.argument(ITEM_ARGUMENT, ItemArgument.item(registryAccess))
                .then(Commands.argument(AMOUNT_ARGUMENT, StringArgumentType.word())
                    .suggests(EconomyCommands::suggestAmounts)
                    .executes(context -> setWorthItem(context, service, ItemArgument.getItem(context, ITEM_ARGUMENT), parseAmount(context, AMOUNT_ARGUMENT, true)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> sellCommand(
        String name,
        CommandBuildContext registryAccess,
        EconomyService service
    ) {
        return Commands.literal(name)
            .then(Commands.literal("hand")
                .executes(context -> sellHeld(context, service, null))
                .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer())
                    .executes(context -> sellHeld(context, service, getInteger(context, AMOUNT_ARGUMENT)))))
            .then(Commands.literal("inventory")
                .executes(context -> sellBulk(context, service, BulkMode.ALL)))
            .then(Commands.literal("all")
                .executes(context -> sellBulk(context, service, BulkMode.ALL)))
            .then(Commands.literal("blocks")
                .executes(context -> sellBulk(context, service, BulkMode.BLOCKS))
                .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer())
                    .executes(context -> sellBulk(context, service, BulkMode.BLOCKS, getInteger(context, AMOUNT_ARGUMENT)))))
            .then(Commands.argument(ITEM_ARGUMENT, ItemArgument.item(registryAccess))
                .executes(context -> sellItem(context, service, ItemArgument.getItem(context, ITEM_ARGUMENT), null))
                .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer())
                    .executes(context -> sellItem(context, service, ItemArgument.getItem(context, ITEM_ARGUMENT), getInteger(context, AMOUNT_ARGUMENT)))));
    }

    private static int balanceSelf(CommandContext<CommandSourceStack> context, EconomyService service) throws CommandSyntaxException {
        EconomyAccount account = service.account(context.getSource().getPlayerOrException());
        context.getSource().sendSystemMessage(tl("balance", "Balance: {0}", money(service, account.balance())));
        return 1;
    }

    private static int balanceTarget(CommandContext<CommandSourceStack> context, EconomyService service) {
        String rawTarget = getString(context, TARGET_ARGUMENT);
        return service.resolveAccount(context.getSource().getServer(), rawTarget)
            .map(account -> {
                context.getSource().sendSystemMessage(tl("balanceOther", "Balance of {0}: {1}", account.listedName(), money(service, account.balance())));
                return 1;
            })
            .orElseGet(() -> {
                context.getSource().sendSystemMessage(tl("playerNotFound", "Player not found."));
                return 0;
            });
    }

    private static int balanceTop(CommandContext<CommandSourceStack> context, EconomyService service, int page) {
        List<EconomyAccount> accounts = service.topBalances(page);
        context.getSource().sendSystemMessage(tl("balanceTop", "Top balances ({0})", Integer.toString(page)));
        if (accounts.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.line("Entries", "none"));
            return 0;
        }
        int offset = (page - 1) * service.settings().balanceTopPageSize();
        for (int index = 0; index < accounts.size(); index++) {
            EconomyAccount account = accounts.get(index);
            context.getSource().sendSystemMessage(tl("balanceTopLine", "{0}. {1}, {2}", Integer.toString(offset + index + 1), account.listedName(), money(service, account.balance())));
        }
        return accounts.size();
    }

    private static int pay(CommandContext<CommandSourceStack> context, EconomyService service, String commandName) throws CommandSyntaxException {
        ServerPlayer payer = context.getSource().getPlayerOrException();
        String rawTarget = getString(context, TARGET_ARGUMENT);
        ServerPlayer receiver = EconomyPlayerLookup.findOnline(context.getSource().getServer(), rawTarget).orElse(null);
        if (receiver == null) {
            context.getSource().sendSystemMessage(tl("payOffline", "You cannot pay offline users."));
            return 0;
        }
        BigDecimal amount = parseAmount(context, AMOUNT_ARGUMENT, false);
        EconomyService.TransferResult result = service.pay(payer, receiver, amount, "/" + commandName + " " + rawTarget + " " + amount.stripTrailingZeros().toPlainString());
        switch (result.status()) {
            case SUCCESS -> {
                payer.sendSystemMessage(tl("moneySentTo", "{0} has been sent to {1}.", money(service, amount), result.receiver().listedName()));
                receiver.sendSystemMessage(tl("moneyRecievedFrom", "{0} has been received from {1}.", money(service, amount), result.payer().listedName()));
                return 1;
            }
            case CONFIRMATION_REQUIRED -> {
                payer.sendSystemMessage(tl("confirmPayment", "To CONFIRM payment of {0}, please repeat command: {1}", money(service, amount), "/" + commandName + " " + rawTarget + " " + amount.stripTrailingZeros().toPlainString()));
                return 0;
            }
            case BELOW_MINIMUM_PAY -> payer.sendSystemMessage(tl("minimumPayAmount", "The minimum amount you can pay is {0}.", money(service, service.settings().minimumPayAmount())));
            case INSUFFICIENT_FUNDS -> payer.sendSystemMessage(Messages.error("Insufficient funds."));
            case RECEIVER_NOT_ACCEPTING -> payer.sendSystemMessage(tl("notAcceptingPay", "{0} is not accepting payment.", result.receiver().listedName()));
            case RECEIVER_WOULD_EXCEED_MAX -> payer.sendSystemMessage(tl("maxMoney", "This transaction would exceed the balance limit for this account."));
            case SELF -> payer.sendSystemMessage(Messages.error("You cannot pay yourself."));
        }
        return 0;
    }

    private static int payToggleSelf(CommandContext<CommandSourceStack> context, EconomyService service, Boolean forcedState) throws CommandSyntaxException {
        EconomyAccount account = service.account(context.getSource().getPlayerOrException());
        EconomyAccount updated = forcedState == null ? service.toggleAcceptingPay(account) : service.setAcceptingPay(account, forcedState);
        context.getSource().sendSystemMessage(tl(updated.acceptingPay() ? "payToggleOn" : "payToggleOff", updated.acceptingPay() ? "You are now accepting payments." : "You are no longer accepting payments."));
        return 1;
    }

    private static int payToggleTarget(CommandContext<CommandSourceStack> context, EconomyService service, Boolean forcedState) {
        String rawTarget = getString(context, TARGET_ARGUMENT);
        return service.resolveAccount(context.getSource().getServer(), rawTarget)
            .map(account -> {
                EconomyAccount updated = forcedState == null ? service.toggleAcceptingPay(account) : service.setAcceptingPay(account, forcedState);
                context.getSource().sendSystemMessage(tl(updated.acceptingPay() ? "payEnabledFor" : "payDisabledFor", updated.acceptingPay() ? "Enabled accepting payments for {0}." : "Disabled accepting payments for {0}.", updated.listedName()));
                return 1;
            })
            .orElseGet(() -> {
                context.getSource().sendSystemMessage(tl("playerNotFound", "Player not found."));
                return 0;
            });
    }

    private static int payConfirmToggle(CommandContext<CommandSourceStack> context, EconomyService service, Boolean forcedState) throws CommandSyntaxException {
        EconomyAccount account = service.account(context.getSource().getPlayerOrException());
        EconomyAccount updated = forcedState == null ? service.toggleConfirmingPayments(account) : service.setConfirmingPayments(account, forcedState);
        context.getSource().sendSystemMessage(tl(updated.confirmingPayments() ? "payConfirmToggleOn" : "payConfirmToggleOff", updated.confirmingPayments() ? "You will now be prompted to confirm payments." : "You will no longer be prompted to confirm payments."));
        return 1;
    }

    private static int eco(
        CommandContext<CommandSourceStack> context,
        EconomyService service,
        EconomyService.EcoOperation operation,
        BigDecimal amount
    ) {
        String rawTarget = getString(context, TARGET_ARGUMENT);
        Optional<EconomyAccount> target = service.resolveAccount(context.getSource().getServer(), rawTarget);
        if (target.isEmpty()) {
            context.getSource().sendSystemMessage(tl("playerNotFound", "Player not found."));
            return 0;
        }

        EconomyService.EcoResult result = service.eco(target.get(), operation, amount);
        if (result.status() == EconomyService.EcoStatus.BELOW_MINIMUM) {
            context.getSource().sendSystemMessage(tl("minimumBalanceError", "The minimum balance a user can have is {0}.", money(service, service.settings().minMoney())));
            return 0;
        }
        if (result.status() == EconomyService.EcoStatus.ABOVE_MAXIMUM) {
            context.getSource().sendSystemMessage(tl("maxMoney", "This transaction would exceed the balance limit for this account."));
            return 0;
        }

        EconomyAccount updated = result.account();
        switch (operation) {
            case GIVE -> context.getSource().sendSystemMessage(tl("addedToOthersAccount", "{0} added to {1} account. New balance: {2}", money(service, amount), updated.listedName(), money(service, updated.balance())));
            case TAKE -> context.getSource().sendSystemMessage(tl("takenFromOthersAccount", "{0} taken from {1} account. New balance: {2}", money(service, amount), updated.listedName(), money(service, updated.balance())));
            case SET, RESET -> context.getSource().sendSystemMessage(tl("setBalOthers", "You set {0}'s balance to {1}.", updated.listedName(), money(service, updated.balance())));
        }
        return 1;
    }

    private static int worthHeld(CommandContext<CommandSourceStack> context, EconomyService service, Integer requestedAmount) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            context.getSource().sendSystemMessage(tl("itemSellAir", "You really tried to sell Air? Put an item in your hand."));
            return 0;
        }
        return sendWorth(context.getSource(), service, stack, selectedAmount(stack.getCount(), requestedAmount));
    }

    private static int worthItem(
        CommandContext<CommandSourceStack> context,
        EconomyService service,
        ItemInput itemInput,
        Integer requestedAmount
    ) throws CommandSyntaxException {
        ItemStack probe = itemInput.createItemStack(1);
        ServerPlayer player = context.getSource().getPlayer();
        int available = player == null ? Integer.MAX_VALUE : countMatching(player.getInventory(), probe.getItem(), BulkMode.ITEM);
        int amount = player == null ? Math.max(1, requestedAmount == null ? 1 : requestedAmount) : selectedAmount(available, requestedAmount);
        return sendWorth(context.getSource(), service, probe, amount);
    }

    private static int worthBulk(CommandContext<CommandSourceStack> context, EconomyService service, BulkMode mode, boolean selling) throws CommandSyntaxException {
        return worthBulk(context, service, mode, selling, null);
    }

    private static int worthBulk(
        CommandContext<CommandSourceStack> context,
        EconomyService service,
        BulkMode mode,
        boolean selling,
        Integer requestedAmount
    ) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        SellScan scan = scanInventory(service, player.getInventory(), mode, requestedAmount, false);
        if (scan.totalWorth().signum() <= 0) {
            context.getSource().sendSystemMessage(tl("itemCannotBeSold", "That item cannot be sold to the server."));
            return 0;
        }
        context.getSource().sendSystemMessage(tl(mode == BulkMode.BLOCKS ? "totalSellableBlocks" : "totalSellableAll", "The total worth of all sellable items and blocks is {1}.", money(service, scan.totalWorth()), money(service, scan.totalWorth())));
        return scan.totalCount();
    }

    private static int setWorthHeld(CommandContext<CommandSourceStack> context, EconomyService service, BigDecimal amount) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            context.getSource().sendSystemMessage(tl("itemSellAir", "You really tried to sell Air? Put an item in your hand."));
            return 0;
        }
        service.store().setWorth(itemId(stack), amount);
        context.getSource().sendSystemMessage(tl("worthSet", "Worth value set"));
        return 1;
    }

    private static int setWorthItem(
        CommandContext<CommandSourceStack> context,
        EconomyService service,
        ItemInput itemInput,
        BigDecimal amount
    ) throws CommandSyntaxException {
        ItemStack probe = itemInput.createItemStack(1);
        service.store().setWorth(itemId(probe), amount);
        context.getSource().sendSystemMessage(tl("worthSet", "Worth value set"));
        return 1;
    }

    private static int sellHeld(CommandContext<CommandSourceStack> context, EconomyService service, Integer requestedAmount) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            player.sendSystemMessage(tl("itemSellAir", "You really tried to sell Air? Put an item in your hand."));
            return 0;
        }
        if (!service.settings().allowSellingNamedItems() && stack.get(DataComponents.CUSTOM_NAME) != null) {
            player.sendSystemMessage(tl("cannotSellNamedItem", "You are not allowed to sell named items."));
            return 0;
        }
        BigDecimal unitWorth = service.store().worth(itemId(stack)).orElse(null);
        if (unitWorth == null) {
            player.sendSystemMessage(tl("itemCannotBeSold", "That item cannot be sold to the server."));
            return 0;
        }
        int amount = selectedAmount(stack.getCount(), requestedAmount);
        if (amount <= 0) {
            player.sendSystemMessage(tl("itemNotEnough1", "You do not have enough of that item to sell."));
            return 0;
        }
        BigDecimal total = unitWorth.multiply(BigDecimal.valueOf(amount));
        EconomyAccount account = service.account(player);
        if (service.settings().wouldExceedMax(account.balance().add(total))) {
            player.sendSystemMessage(tl("maxMoney", "This transaction would exceed the balance limit for this account."));
            return 0;
        }
        stack.shrink(amount);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        service.store().setBalance(account.uuid(), account.balance().add(total));
        player.sendSystemMessage(tl("itemSold", "Sold for {0} ({1} {2} at {3} each).", money(service, total), Integer.toString(amount), itemName(stack), money(service, unitWorth)));
        return amount;
    }

    private static int sellItem(
        CommandContext<CommandSourceStack> context,
        EconomyService service,
        ItemInput itemInput,
        Integer requestedAmount
    ) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack probe = itemInput.createItemStack(1);
        BigDecimal unitWorth = service.store().worth(itemId(probe)).orElse(null);
        if (unitWorth == null) {
            player.sendSystemMessage(tl("itemCannotBeSold", "That item cannot be sold to the server."));
            return 0;
        }
        int available = countMatching(player.getInventory(), probe.getItem(), BulkMode.ITEM, service.settings().allowSellingNamedItems());
        int amount = selectedAmount(available, requestedAmount);
        if (amount <= 0) {
            player.sendSystemMessage(tl("itemNotEnough1", "You do not have enough of that item to sell."));
            return 0;
        }
        return completeBulkSale(
            player,
            service,
            Map.of(probe.getItem(), amount),
            unitWorth.multiply(BigDecimal.valueOf(amount)),
            amount,
            false,
            service.settings().allowSellingNamedItems()
        );
    }

    private static int sellBulk(CommandContext<CommandSourceStack> context, EconomyService service, BulkMode mode) throws CommandSyntaxException {
        return sellBulk(context, service, mode, null);
    }

    private static int sellBulk(
        CommandContext<CommandSourceStack> context,
        EconomyService service,
        BulkMode mode,
        Integer requestedAmount
    ) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        SellScan scan = scanInventory(service, player.getInventory(), mode, requestedAmount, true);
        if (scan.totalWorth().signum() <= 0 || scan.totalCount() <= 0) {
            player.sendSystemMessage(tl("itemCannotBeSold", "That item cannot be sold to the server."));
            return 0;
        }
        return completeBulkSale(
            player,
            service,
            scan.itemCounts(),
            scan.totalWorth(),
            scan.totalCount(),
            mode == BulkMode.BLOCKS,
            service.settings().allowSellingNamedItems()
        );
    }

    private static int completeBulkSale(
        ServerPlayer player,
        EconomyService service,
        Map<Item, Integer> itemCounts,
        BigDecimal totalWorth,
        int totalCount,
        boolean blocksOnly,
        boolean allowNamedItems
    ) {
        EconomyAccount account = service.account(player);
        if (service.settings().wouldExceedMax(account.balance().add(totalWorth))) {
            player.sendSystemMessage(tl("maxMoney", "This transaction would exceed the balance limit for this account."));
            return 0;
        }
        for (Map.Entry<Item, Integer> entry : itemCounts.entrySet()) {
            removeMatching(player.getInventory(), entry.getKey(), entry.getValue(), allowNamedItems);
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        service.store().setBalance(account.uuid(), account.balance().add(totalWorth));
        player.sendSystemMessage(tl(blocksOnly ? "totalWorthBlocks" : "totalWorthAll", "Sold all items and blocks for a total worth of {1}.", money(service, totalWorth), money(service, totalWorth)));
        return totalCount;
    }

    private static int sendWorth(CommandSourceStack source, EconomyService service, ItemStack stack, int amount) {
        BigDecimal unitWorth = service.store().worth(itemId(stack)).orElse(null);
        if (unitWorth == null) {
            source.sendSystemMessage(tl("itemCannotBeSold", "That item cannot be sold to the server."));
            return 0;
        }
        if (amount <= 0) {
            source.sendSystemMessage(tl("itemNotEnough1", "You do not have enough of that item to sell."));
            return 0;
        }
        BigDecimal total = unitWorth.multiply(BigDecimal.valueOf(amount));
        source.sendSystemMessage(tl("worth", "Stack of {0} worth {1} ({2} item(s) at {3} each)", itemName(stack), money(service, total), Integer.toString(amount), money(service, unitWorth)));
        return amount;
    }

    private static SellScan scanInventory(
        EconomyService service,
        Inventory inventory,
        BulkMode mode,
        Integer requestedAmount,
        boolean skipNamedItems
    ) {
        Map<Item, Integer> itemCounts = new LinkedHashMap<>();
        BigDecimal totalWorth = BigDecimal.ZERO;
        int remaining = requestedAmount == null || requestedAmount < 0 ? Integer.MAX_VALUE : requestedAmount;
        int totalCount = 0;

        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !matchesMode(stack, mode)) {
                continue;
            }
            if (skipNamedItems && !service.settings().allowSellingNamedItems() && stack.get(DataComponents.CUSTOM_NAME) != null) {
                continue;
            }
            BigDecimal unitWorth = service.store().worth(itemId(stack)).orElse(null);
            if (unitWorth == null) {
                continue;
            }
            int amount = Math.min(stack.getCount(), remaining);
            itemCounts.merge(stack.getItem(), amount, Integer::sum);
            totalWorth = totalWorth.add(unitWorth.multiply(BigDecimal.valueOf(amount)));
            totalCount += amount;
            remaining -= amount;
        }

        if (requestedAmount != null && requestedAmount < 0) {
            int keep = Math.abs(requestedAmount);
            return trimScanToCount(service, inventory, mode, skipNamedItems, Math.max(0, totalCount - keep));
        }
        return new SellScan(itemCounts, totalWorth, totalCount);
    }

    private static SellScan trimScanToCount(
        EconomyService service,
        Inventory inventory,
        BulkMode mode,
        boolean skipNamedItems,
        int maxCount
    ) {
        Map<Item, Integer> itemCounts = new LinkedHashMap<>();
        BigDecimal totalWorth = BigDecimal.ZERO;
        int remaining = maxCount;
        int totalCount = 0;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !matchesMode(stack, mode)) {
                continue;
            }
            if (skipNamedItems && !service.settings().allowSellingNamedItems() && stack.get(DataComponents.CUSTOM_NAME) != null) {
                continue;
            }
            BigDecimal unitWorth = service.store().worth(itemId(stack)).orElse(null);
            if (unitWorth == null) {
                continue;
            }
            int amount = Math.min(stack.getCount(), remaining);
            itemCounts.merge(stack.getItem(), amount, Integer::sum);
            totalWorth = totalWorth.add(unitWorth.multiply(BigDecimal.valueOf(amount)));
            totalCount += amount;
            remaining -= amount;
        }
        return new SellScan(itemCounts, totalWorth, totalCount);
    }

    private static boolean matchesMode(ItemStack stack, BulkMode mode) {
        return switch (mode) {
            case ALL -> true;
            case BLOCKS -> stack.getItem() instanceof BlockItem;
            case ITEM -> true;
        };
    }

    private static int selectedAmount(int available, Integer requestedAmount) {
        if (requestedAmount == null) {
            return available;
        }
        if (requestedAmount < 0) {
            return Math.max(0, available + requestedAmount);
        }
        return Math.min(available, requestedAmount);
    }

    private static int countMatching(Inventory inventory, Item item, BulkMode mode) {
        return countMatching(inventory, item, mode, true);
    }

    private static int countMatching(Inventory inventory, Item item, BulkMode mode, boolean allowNamedItems) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()
                && stack.getItem() == item
                && matchesMode(stack, mode)
                && (allowNamedItems || stack.get(DataComponents.CUSTOM_NAME) == null)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void removeMatching(Inventory inventory, Item item, int amount, boolean allowNamedItems) {
        int remaining = amount;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.getItem() != item || (!allowNamedItems && stack.get(DataComponents.CUSTOM_NAME) != null)) {
                continue;
            }
            int removed = Math.min(stack.getCount(), remaining);
            stack.shrink(removed);
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
            remaining -= removed;
        }
    }

    private static BigDecimal parseAmount(CommandContext<CommandSourceStack> context, String argumentName, boolean allowZero) {
        String rawValue = getString(context, argumentName);
        String sanitized = rawValue
            .replace(context.getSource().getServer() == null ? "$" : "$", "")
            .replace(",", "")
            .trim();
        if (sanitized.startsWith("+")) {
            sanitized = sanitized.substring(1);
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(sanitized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid amount: " + rawValue, exception);
        }
        if (allowZero ? amount.signum() < 0 : amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be " + (allowZero ? "zero or positive" : "positive") + ": " + rawValue);
        }
        return amount;
    }

    private static String money(EconomyService service, BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(RoundingMode.HALF_UP);
        String number = format.format(amount);
        if (service.settings().currencySymbolSuffix()) {
            return number + service.settings().currencySymbol();
        }
        return service.settings().currencySymbol() + number;
    }

    private static Identifier itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private static String itemName(ItemStack stack) {
        Identifier id = itemId(stack);
        return id == null ? stack.getHoverName().getString() : id.toString();
    }

    private static Component tl(String key, String fallback, Object... arguments) {
        String message = UPSTREAM_MESSAGES.messageOrDefault(Locale.ROOT, key, fallback, arguments);
        return Component.literal(MESSAGE_TAG.matcher(message).replaceAll(""));
    }

    private static CompletableFuture<Suggestions> suggestOnlinePlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(EconomyPlayerLookup.onlineSuggestions(context.getSource().getServer()), builder);
    }

    private static CompletableFuture<Suggestions> suggestAccounts(
        CommandContext<CommandSourceStack> context,
        EconomyService service,
        SuggestionsBuilder builder
    ) {
        Set<String> suggestions = new LinkedHashSet<>(EconomyPlayerLookup.onlineSuggestions(context.getSource().getServer()));
        for (EconomyAccount account : service.store().accounts()) {
            suggestions.add(account.accountName());
            if (!account.displayName().equals(account.accountName())) {
                suggestions.add(account.displayName().contains(" ") ? "\"" + account.displayName().replace("\"", "\\\"") + "\"" : account.displayName());
            }
        }
        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private static CompletableFuture<Suggestions> suggestAmounts(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(List.of("1", "10", "100", "1000").stream(), builder);
    }

    private static Boolean forcedPayToggleState(String commandName) {
        String folded = commandName.toLowerCase(Locale.ROOT);
        if (folded.contains("payon")) {
            return true;
        }
        if (folded.contains("payoff")) {
            return false;
        }
        return null;
    }

    private static Boolean forcedPayConfirmState(String commandName) {
        String folded = commandName.toLowerCase(Locale.ROOT);
        if (folded.contains("payconfirmon")) {
            return true;
        }
        if (folded.contains("payconfirmoff")) {
            return false;
        }
        return null;
    }

    private enum BulkMode {
        ALL,
        BLOCKS,
        ITEM
    }

    private record SellScan(Map<Item, Integer> itemCounts, BigDecimal totalWorth, int totalCount) {
    }

    @FunctionalInterface
    private interface CommandFactory {
        LiteralArgumentBuilder<CommandSourceStack> create(String name);
    }
}
