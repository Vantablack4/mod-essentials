package com.vantablack4.essentials.commands.social;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.vantablack4.essentials.Messages;
import com.vantablack4.essentials.PermissionChecks;
import com.vantablack4.essentials.VantablackEssentialsMod;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class SocialCommands {
    private static final String TARGET_ARGUMENT = "target";
    private static final String TARGET_OR_STATE_ARGUMENT = "targetOrState";
    private static final String STATE_ARGUMENT = "state";
    private static final String MESSAGE_ARGUMENT = "message";
    private static final String INDEX_ARGUMENT = "index";
    private static final String PAGE_ARGUMENT = "page";
    private static final String NICKNAME_ARGUMENT = "nickname";
    private static final int MAIL_PAGE_SIZE = 8;
    private static final int MAX_NICKNAME_LENGTH = 32;

    private final PermissionChecks permissions;
    private final SocialStateService state;
    private final MailStore mailStore;

    public SocialCommands(Path configDirectory, PermissionChecks permissions) {
        this.permissions = permissions;
        this.state = new SocialStateService(configDirectory);
        this.mailStore = new MailStore(configDirectory);
    }

    public void registerLifecycle() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            state.nickname(player.getUUID()).ifPresent(nickname -> applyNickname(player, nickname));
            long unread = mailStore.unreadCount(player);
            if (unread > 0) {
                player.sendSystemMessage(Messages.success("You have " + unread + " mail message(s). Type /mail read to view your mail."));
            }
        });
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerAll(dispatcher, this::msgCommand, "msg", "w", "m", "t", "pm", "emsg", "epm", "tell", "etell", "whisper", "ewhisper");
        registerAll(dispatcher, this::replyCommand, "r", "er", "reply", "ereply");
        registerAll(dispatcher, this::ignoreCommand, "ignore", "eignore", "unignore", "eunignore", "delignore", "edelignore", "remignore", "eremignore", "rmignore", "ermignore");
        registerAll(dispatcher, this::messageToggleCommand, "msgtoggle", "emsgtoggle");
        registerAll(dispatcher, this::replyToggleCommand, "rtoggle", "ertoggle", "replytoggle", "ereplytoggle");
        registerAll(dispatcher, this::socialSpyCommand, "socialspy", "esocialspy");
        registerAll(dispatcher, this::helpOpCommand, "helpop", "ac", "eac", "amsg", "eamsg", "ehelpop");
        registerAll(dispatcher, this::meCommand, "me", "action", "eaction", "describe", "edescribe", "eme");
        registerAll(dispatcher, this::afkCommand, "afk", "eafk", "away", "eaway");
        registerAll(dispatcher, this::mailCommand, "mail", "email", "eemail", "memo", "ememo");
        registerAll(dispatcher, this::nickCommand, "nick", "enick", "nickname", "enickname");
        registerAll(dispatcher, this::toggleShoutCommand, "toggleshout", "etoggleshout");
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

    private LiteralArgumentBuilder<CommandSourceStack> msgCommand(String name) {
        return Commands.literal(name)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .then(Commands.argument(MESSAGE_ARGUMENT, StringArgumentType.greedyString())
                    .executes(context -> sendPrivateMessage(
                        context,
                        resolveTarget(context, TARGET_ARGUMENT),
                        getString(context, MESSAGE_ARGUMENT)
                    ))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> replyCommand(String name) {
        return Commands.literal(name)
            .then(Commands.argument(MESSAGE_ARGUMENT, StringArgumentType.greedyString())
                .executes(this::reply));
    }

    private LiteralArgumentBuilder<CommandSourceStack> ignoreCommand(String name) {
        return Commands.literal(name)
            .executes(this::ignoreList)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(this::ignoreToggle));
    }

    private LiteralArgumentBuilder<CommandSourceStack> messageToggleCommand(String name) {
        return playerToggleCommand(name, this::messageToggle, false);
    }

    private LiteralArgumentBuilder<CommandSourceStack> replyToggleCommand(String name) {
        return playerToggleCommand(name, this::replyToggle, false);
    }

    private LiteralArgumentBuilder<CommandSourceStack> socialSpyCommand(String name) {
        return playerToggleCommand(name, this::socialSpyToggle, true).requires(permissions::admin);
    }

    private LiteralArgumentBuilder<CommandSourceStack> toggleShoutCommand(String name) {
        return playerToggleCommand(name, this::shoutToggle, false);
    }

    private LiteralArgumentBuilder<CommandSourceStack> playerToggleCommand(String name, ToggleHandler handler, boolean adminOnly) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal(name)
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                return handler.apply(context, player, null);
            })
            .then(Commands.argument(TARGET_OR_STATE_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayersAndToggleStates)
                .executes(context -> toggleTargetOrSelf(context, handler, getString(context, TARGET_OR_STATE_ARGUMENT)))
                .then(Commands.argument(STATE_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestToggleStates)
                    .executes(context -> {
                        ServerPlayer target = resolveTarget(context, TARGET_OR_STATE_ARGUMENT);
                        return target == null ? 0 : handler.apply(context, target, parseToggle(getString(context, STATE_ARGUMENT)));
                    })));
        return adminOnly ? command.requires(permissions::admin) : command;
    }

    private LiteralArgumentBuilder<CommandSourceStack> helpOpCommand(String name) {
        return Commands.literal(name)
            .then(Commands.argument(MESSAGE_ARGUMENT, StringArgumentType.greedyString())
                .executes(this::helpOp));
    }

    private LiteralArgumentBuilder<CommandSourceStack> meCommand(String name) {
        return Commands.literal(name)
            .then(Commands.argument(MESSAGE_ARGUMENT, StringArgumentType.greedyString())
                .executes(this::me));
    }

    private LiteralArgumentBuilder<CommandSourceStack> afkCommand(String name) {
        return Commands.literal(name)
            .executes(context -> afk(context, null))
            .then(Commands.argument(MESSAGE_ARGUMENT, StringArgumentType.greedyString())
                .suggests(this::suggestPlayers)
                .executes(context -> afk(context, getString(context, MESSAGE_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> mailCommand(String name) {
        return Commands.literal(name)
            .executes(context -> mailRead(context, 1))
            .then(Commands.literal("read")
                .executes(context -> mailRead(context, 1))
                .then(Commands.argument(PAGE_ARGUMENT, IntegerArgumentType.integer(1))
                    .executes(context -> mailRead(context, getInteger(context, PAGE_ARGUMENT)))))
            .then(Commands.literal("send")
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestPlayers)
                    .then(Commands.argument(MESSAGE_ARGUMENT, StringArgumentType.greedyString())
                        .executes(this::mailSend))))
            .then(Commands.literal("sendall")
                .requires(permissions::admin)
                .then(Commands.argument(MESSAGE_ARGUMENT, StringArgumentType.greedyString())
                    .executes(this::mailSendAll)))
            .then(Commands.literal("clear")
                .executes(this::mailClear)
                .then(Commands.argument(INDEX_ARGUMENT, IntegerArgumentType.integer(1))
                    .executes(this::mailDelete)))
            .then(Commands.literal("delete")
                .then(Commands.argument(INDEX_ARGUMENT, IntegerArgumentType.integer(1))
                    .executes(this::mailDelete)))
            .then(Commands.literal("clearall")
                .requires(permissions::admin)
                .executes(this::mailClearAll));
    }

    private LiteralArgumentBuilder<CommandSourceStack> nickCommand(String name) {
        return Commands.literal(name)
            .then(Commands.argument(TARGET_OR_STATE_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayersAndOff)
                .executes(context -> nickSelf(context, getString(context, TARGET_OR_STATE_ARGUMENT)))
                .then(Commands.argument(NICKNAME_ARGUMENT, StringArgumentType.greedyString())
                    .requires(permissions::admin)
                    .executes(context -> {
                        ServerPlayer target = resolveTarget(context, TARGET_OR_STATE_ARGUMENT);
                        return target == null ? 0 : nick(context, target, getString(context, NICKNAME_ARGUMENT), true);
                    })));
    }

    private int sendPrivateMessage(CommandContext<CommandSourceStack> context, ServerPlayer target, String message) throws CommandSyntaxException {
        if (target == null) {
            return 0;
        }
        if (message.isBlank()) {
            context.getSource().sendSystemMessage(Messages.usage("/msg <player> <message>"));
            return 0;
        }

        ServerPlayer sender = context.getSource().getPlayer();
        if (sender != null && sender.getUUID().equals(target.getUUID())) {
            sender.sendSystemMessage(Messages.error("You cannot privately message yourself."));
            return 0;
        }
        if (!canReceiveMessage(context.getSource(), sender, target)) {
            return 0;
        }

        String senderName = sourceDisplayName(context.getSource());
        String targetName = SocialPlayerLookup.displayName(target);
        Component toSender = privateMessageComponent("me", targetName, message);
        Component toTarget = privateMessageComponent(senderName, "me", message);
        context.getSource().sendSystemMessage(toSender);
        target.sendSystemMessage(toTarget);

        if (sender != null) {
            state.senderMessaged(sender.getUUID(), target.getUUID());
            state.recipientReceived(target.getUUID(), sender.getUUID());
            state.afkStatus(target.getUUID()).ifPresent(status -> sendAfkNotice(sender, target, status));
        }
        socialSpy(context.getSource().getServer(), sender, target, senderName, targetName, message);
        return 1;
    }

    private int reply(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer sender = context.getSource().getPlayerOrException();
        Optional<UUID> replyTarget = state.replyTarget(sender.getUUID());
        if (replyTarget.isEmpty()) {
            sender.sendSystemMessage(Messages.error("You have nobody to whom you can reply."));
            return 0;
        }
        ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayer(replyTarget.get());
        if (target == null) {
            sender.sendSystemMessage(Messages.error("Your reply target is offline."));
            return 0;
        }
        return sendPrivateMessage(context, target, getString(context, MESSAGE_ARGUMENT));
    }

    private boolean canReceiveMessage(CommandSourceStack source, ServerPlayer sender, ServerPlayer target) {
        if (sender != null && state.isIgnored(target.getUUID(), sender.getUUID())) {
            source.sendSystemMessage(Messages.error(SocialPlayerLookup.displayName(target) + " is ignoring you."));
            return false;
        }
        if (state.messagesDisabled(target.getUUID()) && !permissions.admin(source)) {
            source.sendSystemMessage(Messages.error(SocialPlayerLookup.displayName(target) + " has messages disabled."));
            return false;
        }
        return true;
    }

    private int ignoreList(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Set<UUID> ignored = state.ignoredPlayers(player.getUUID());
        if (ignored.isEmpty()) {
            player.sendSystemMessage(Messages.line("Ignored", "none"));
            return 1;
        }
        List<String> names = ignored.stream()
            .map(uuid -> displayKnownPlayer(context.getSource().getServer(), uuid))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        player.sendSystemMessage(Messages.line("Ignored", String.join(", ", names)));
        return names.size();
    }

    private int ignoreToggle(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
        if (target == null) {
            return 0;
        }
        if (player.getUUID().equals(target.getUUID())) {
            player.sendSystemMessage(Messages.error("Ignoring yourself will not solve anything."));
            return 0;
        }
        boolean ignored = state.toggleIgnored(player.getUUID(), target.getUUID());
        player.sendSystemMessage(Messages.success((ignored ? "Now ignoring " : "No longer ignoring ") + SocialPlayerLookup.displayName(target) + "."));
        return 1;
    }

    private int toggleTargetOrSelf(CommandContext<CommandSourceStack> context, ToggleHandler handler, String rawTargetOrState) throws CommandSyntaxException {
        Boolean toggle = parseToggle(rawTargetOrState);
        if (toggle != null) {
            return handler.apply(context, context.getSource().getPlayerOrException(), toggle);
        }
        if (!permissions.admin(context.getSource())) {
            context.getSource().sendSystemMessage(Messages.error("You can only change your own state."));
            return 0;
        }
        ServerPlayer target = resolveTarget(context, TARGET_OR_STATE_ARGUMENT);
        return target == null ? 0 : handler.apply(context, target, null);
    }

    private int messageToggle(CommandContext<CommandSourceStack> context, ServerPlayer target, Boolean disabled) {
        boolean next = state.setMessagesDisabled(target.getUUID(), disabled);
        target.sendSystemMessage(Messages.success("Receiving private messages " + (next ? "disabled" : "enabled") + "."));
        echoIfOther(context, target, "Receiving private messages " + (next ? "disabled" : "enabled") + " for " + SocialPlayerLookup.displayName(target) + ".");
        return 1;
    }

    private int replyToggle(CommandContext<CommandSourceStack> context, ServerPlayer target, Boolean enabled) {
        boolean next = state.setReplyToLastRecipient(target.getUUID(), enabled);
        target.sendSystemMessage(Messages.success("Replying to last message recipient " + (next ? "enabled" : "disabled") + "."));
        echoIfOther(context, target, "Replying to last message recipient " + (next ? "enabled" : "disabled") + " for " + SocialPlayerLookup.displayName(target) + ".");
        return 1;
    }

    private int socialSpyToggle(CommandContext<CommandSourceStack> context, ServerPlayer target, Boolean enabled) {
        boolean next = state.setSocialSpy(target.getUUID(), enabled);
        target.sendSystemMessage(Messages.success("SocialSpy for " + SocialPlayerLookup.displayName(target) + ": " + (next ? "enabled" : "disabled") + "."));
        echoIfOther(context, target, "SocialSpy for " + SocialPlayerLookup.displayName(target) + ": " + (next ? "enabled" : "disabled") + ".");
        return 1;
    }

    private int shoutToggle(CommandContext<CommandSourceStack> context, ServerPlayer target, Boolean enabled) {
        boolean next = state.setShout(target.getUUID(), enabled);
        target.sendSystemMessage(Messages.success("Shout mode " + (next ? "enabled" : "disabled") + "."));
        echoIfOther(context, target, "Shout mode " + (next ? "enabled" : "disabled") + " for " + SocialPlayerLookup.displayName(target) + ".");
        if (next) {
            target.sendSystemMessage(Messages.line("Scope", "mod-roleplay owns live chat formatting; this stores the EssentialsX shout preference for integrations."));
        }
        return 1;
    }

    private int helpOp(CommandContext<CommandSourceStack> context) {
        String message = getString(context, MESSAGE_ARGUMENT);
        String senderName = sourceDisplayName(context.getSource());
        Component component = Component.literal("[HelpOp] ")
            .withStyle(ChatFormatting.RED)
            .append(Component.literal(senderName + ": ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(message).withStyle(ChatFormatting.WHITE));
        int recipients = 0;
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            if (permissions.admin(player.createCommandSourceStack())) {
                player.sendSystemMessage(component);
                recipients++;
            }
        }
        context.getSource().sendSystemMessage(Messages.success("HelpOp sent to " + recipients + " admin(s)."));
        VantablackEssentialsMod.LOGGER.info("[HelpOp] {}: {}", senderName, message);
        return Math.max(1, recipients);
    }

    private int me(CommandContext<CommandSourceStack> context) {
        String message = getString(context, MESSAGE_ARGUMENT);
        ServerPlayer sender = context.getSource().getPlayer();
        String senderName = sourceDisplayName(context.getSource());
        Component action = Component.literal("* " + senderName + " " + message).withStyle(ChatFormatting.LIGHT_PURPLE);
        int recipients = 0;
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            if (sender == null || !state.isIgnored(player.getUUID(), sender.getUUID())) {
                player.sendSystemMessage(action);
                recipients++;
            }
        }
        if (sender == null) {
            context.getSource().sendSystemMessage(action);
        }
        return Math.max(1, recipients);
    }

    private int afk(CommandContext<CommandSourceStack> context, String rawArgument) throws CommandSyntaxException {
        ServerPlayer executor = context.getSource().getPlayerOrException();
        ServerPlayer target = executor;
        String message = rawArgument;
        if (rawArgument != null && permissions.admin(context.getSource())) {
            TargetAndMessage parsed = parseTargetAndMessage(context.getSource().getServer(), rawArgument);
            if (parsed.target() != null) {
                target = parsed.target();
                message = parsed.message();
            }
        }

        if (state.isAfk(target.getUUID())) {
            state.clearAfk(target.getUUID());
            broadcast(context.getSource().getServer(), Messages.success(SocialPlayerLookup.displayName(target) + " is no longer AFK."));
        } else {
            SocialStateService.AfkStatus status = state.setAfk(target.getUUID(), message);
            broadcast(context.getSource().getServer(), afkComponent(target, status));
        }
        return 1;
    }

    private int mailRead(CommandContext<CommandSourceStack> context, int page) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        List<MailStore.MailEntry> mail = mailStore.readFor(player);
        if (mail.isEmpty()) {
            player.sendSystemMessage(Messages.success("You do not have any mail."));
            return 1;
        }
        int pages = Math.max(1, (int) Math.ceil(mail.size() / (double) MAIL_PAGE_SIZE));
        int currentPage = Math.max(1, Math.min(page, pages));
        int start = (currentPage - 1) * MAIL_PAGE_SIZE;
        int end = Math.min(mail.size(), start + MAIL_PAGE_SIZE);
        player.sendSystemMessage(Messages.header("Mail " + currentPage + "/" + pages));
        for (int index = start; index < end; index++) {
            MailStore.MailEntry entry = mail.get(index);
            player.sendSystemMessage(Component.literal((index + 1) + ". ")
                .withStyle(entry.read() ? ChatFormatting.GRAY : ChatFormatting.GREEN)
                .append(Component.literal("[" + MailStore.formatDate(entry.createdEpochMillis()) + "] ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(entry.senderName() + ": ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(entry.message()).withStyle(ChatFormatting.WHITE)));
        }
        player.sendSystemMessage(Messages.usage("/mail clear | /mail delete <number>"));
        return mail.size();
    }

    private int mailSend(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String message = getString(context, MESSAGE_ARGUMENT);
        if (message.length() > MailStore.MAX_MESSAGE_LENGTH) {
            context.getSource().sendSystemMessage(Messages.error("Mail message too long. Keep it below " + MailStore.MAX_MESSAGE_LENGTH + " characters."));
            return 0;
        }
        MailStore.MailRecipient recipient = mailStore.recipientFor(context.getSource().getServer(), getString(context, TARGET_ARGUMENT));
        MailStore.MailSender sender = context.getSource().getPlayer() == null
            ? mailStore.consoleSender()
            : mailStore.senderFor(context.getSource().getPlayerOrException());
        MailStore.MailEntry entry = mailStore.send(recipient, sender, message);
        context.getSource().sendSystemMessage(Messages.success("Mail sent to " + recipient.displayName() + "."));
        SocialPlayerLookup.findOnline(context.getSource().getServer(), recipient.displayName())
            .ifPresent(player -> player.sendSystemMessage(Messages.success("You have new mail from " + sender.displayName() + ". Type /mail read to view it.")));
        socialSpyMail(context.getSource().getServer(), context.getSource().getPlayer(), sender.displayName(), recipient.displayName(), entry.message());
        return 1;
    }

    private int mailSendAll(CommandContext<CommandSourceStack> context) {
        String message = getString(context, MESSAGE_ARGUMENT);
        if (message.length() > MailStore.MAX_MESSAGE_LENGTH) {
            context.getSource().sendSystemMessage(Messages.error("Mail message too long. Keep it below " + MailStore.MAX_MESSAGE_LENGTH + " characters."));
            return 0;
        }
        MailStore.MailSender sender = context.getSource().getPlayer() == null
            ? mailStore.consoleSender()
            : mailStore.senderFor(context.getSource().getPlayer());
        int count = 0;
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            mailStore.send(new MailStore.MailRecipient("uuid:" + player.getUUID(), SocialPlayerLookup.displayName(player)), sender, message);
            player.sendSystemMessage(Messages.success("You have new mail from " + sender.displayName() + ". Type /mail read to view it."));
            count++;
        }
        context.getSource().sendSystemMessage(Messages.success("Mail sent to " + count + " online player(s)."));
        return count;
    }

    private int mailClear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int removed = mailStore.clear(player);
        player.sendSystemMessage(Messages.success(removed == 0 ? "You do not have any mail." : "Mail cleared."));
        return Math.max(1, removed);
    }

    private int mailDelete(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int index = getInteger(context, INDEX_ARGUMENT);
        Optional<MailStore.MailEntry> removed = mailStore.delete(player, index);
        if (removed.isEmpty()) {
            player.sendSystemMessage(Messages.error("You must specify a mail number in your mailbox."));
            return 0;
        }
        player.sendSystemMessage(Messages.success("Mail deleted: " + index));
        return 1;
    }

    private int mailClearAll(CommandContext<CommandSourceStack> context) {
        int removed = mailStore.clearAll();
        context.getSource().sendSystemMessage(Messages.success("Mail cleared for all local mailboxes: " + removed + " message(s)."));
        return Math.max(1, removed);
    }

    private int nickSelf(CommandContext<CommandSourceStack> context, String nickname) throws CommandSyntaxException {
        return nick(context, context.getSource().getPlayerOrException(), nickname, false);
    }

    private int nick(CommandContext<CommandSourceStack> context, ServerPlayer target, String rawNickname, boolean other) {
        String nickname = SocialPlayerLookup.cleanInput(rawNickname);
        if (nickname.equalsIgnoreCase("off")) {
            state.setNickname(target.getUUID(), null);
            clearNickname(target);
            target.sendSystemMessage(Messages.success("You no longer have a nickname."));
            echoIfOther(context, target, "Nickname removed for " + target.getScoreboardName() + ".");
            return 1;
        }
        if (!validNickname(nickname)) {
            context.getSource().sendSystemMessage(Messages.error("Nicknames must be 1-" + MAX_NICKNAME_LENGTH + " printable characters and cannot use control codes."));
            return 0;
        }
        if (nicknameInUse(context.getSource().getServer(), target, nickname)) {
            context.getSource().sendSystemMessage(Messages.error("That nickname is already in use."));
            return 0;
        }
        state.setNickname(target.getUUID(), nickname);
        applyNickname(target, nickname);
        target.sendSystemMessage(Messages.success("Your nickname is now " + nickname + "."));
        echoIfOther(context, target, "Nickname changed for " + target.getScoreboardName() + " to " + nickname + ".");
        if (other) {
            context.getSource().sendSystemMessage(Messages.line("Fabric limitation", "This updates the server-side entity display/custom name. Vanilla signed chat and tab-list names need a dedicated mixin/packet bridge for full Bukkit display-name parity."));
        }
        return 1;
    }

    private ServerPlayer resolveTarget(CommandContext<CommandSourceStack> context, String argumentName) {
        String rawTarget = getString(context, argumentName);
        return SocialPlayerLookup.findOnline(context.getSource().getServer(), rawTarget)
            .orElseGet(() -> {
                context.getSource().sendSystemMessage(Messages.error("Player not found: " + rawTarget));
                return null;
            });
    }

    private CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(SocialPlayerLookup.suggestions(context.getSource().getServer()), builder);
    }

    private CompletableFuture<Suggestions> suggestPlayersAndToggleStates(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> suggestions = new LinkedHashSet<>(List.of("on", "off", "enable", "disable"));
        if (permissions.admin(context.getSource())) {
            suggestions.addAll(SocialPlayerLookup.suggestions(context.getSource().getServer()));
        }
        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private CompletableFuture<Suggestions> suggestToggleStates(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(List.of("on", "off", "enable", "disable"), builder);
    }

    private CompletableFuture<Suggestions> suggestPlayersAndOff(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> suggestions = new LinkedHashSet<>(List.of("off"));
        suggestions.addAll(SocialPlayerLookup.suggestions(context.getSource().getServer()));
        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private void socialSpy(MinecraftServer server, ServerPlayer sender, ServerPlayer target, String senderName, String targetName, String message) {
        Component spy = Component.literal("[SocialSpy] ")
            .withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(senderName + " -> " + targetName + ": ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(message).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (state.socialSpy(player.getUUID())
                && (sender == null || !player.getUUID().equals(sender.getUUID()))
                && !player.getUUID().equals(target.getUUID())) {
                player.sendSystemMessage(spy);
            }
        }
    }

    private void socialSpyMail(MinecraftServer server, ServerPlayer sender, String senderName, String targetName, String message) {
        Component spy = Component.literal("[SocialSpy] ")
            .withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal("mail " + senderName + " -> " + targetName + ": ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(message).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (state.socialSpy(player.getUUID()) && (sender == null || !player.getUUID().equals(sender.getUUID()))) {
                player.sendSystemMessage(spy);
            }
        }
    }

    private void sendAfkNotice(ServerPlayer sender, ServerPlayer target, SocialStateService.AfkStatus status) {
        String text = status.message() == null
            ? SocialPlayerLookup.displayName(target) + " is AFK."
            : SocialPlayerLookup.displayName(target) + " is AFK: " + status.message();
        sender.sendSystemMessage(Messages.success(text));
    }

    private Component afkComponent(ServerPlayer player, SocialStateService.AfkStatus status) {
        String text = status.message() == null
            ? SocialPlayerLookup.displayName(player) + " is now AFK."
            : SocialPlayerLookup.displayName(player) + " is now AFK: " + status.message();
        return Messages.success(text);
    }

    private void broadcast(MinecraftServer server, Component component) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(component);
        }
    }

    private void echoIfOther(CommandContext<CommandSourceStack> context, ServerPlayer target, String message) {
        ServerPlayer sender = context.getSource().getPlayer();
        if (sender == null || !sender.getUUID().equals(target.getUUID())) {
            context.getSource().sendSystemMessage(Messages.success(message));
        }
    }

    private static Component privateMessageComponent(String sender, String recipient, String message) {
        return Component.literal("[")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(sender).withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" -> ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(recipient).withStyle(ChatFormatting.GOLD))
            .append(Component.literal("] ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(message).withStyle(ChatFormatting.WHITE));
    }

    private static String sourceDisplayName(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null ? "Console" : SocialPlayerLookup.displayName(player);
    }

    private static Boolean parseToggle(String raw) {
        String value = raw.toLowerCase(Locale.ROOT).trim();
        return switch (value) {
            case "on", "enable", "enabled", "true", "yes", "1" -> true;
            case "off", "disable", "disabled", "false", "no", "0" -> false;
            default -> null;
        };
    }

    private static String displayKnownPlayer(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return SocialPlayerLookup.displayName(online);
        }
        return server.services().nameToIdCache().get(uuid)
            .map(profile -> profile.name() + " (" + uuid + ")")
            .orElse(uuid.toString());
    }

    private static TargetAndMessage parseTargetAndMessage(MinecraftServer server, String rawArgument) {
        String cleaned = SocialPlayerLookup.cleanInput(rawArgument);
        String[] parts = cleaned.split("\\s+", 2);
        if (parts.length == 0 || parts[0].isBlank()) {
            return new TargetAndMessage(null, cleaned);
        }
        Optional<ServerPlayer> target = SocialPlayerLookup.findOnline(server, parts[0]);
        return target.map(player -> new TargetAndMessage(player, parts.length > 1 ? parts[1] : null))
            .orElseGet(() -> new TargetAndMessage(null, cleaned));
    }

    private static boolean validNickname(String nickname) {
        if (nickname == null || nickname.isBlank() || nickname.length() > MAX_NICKNAME_LENGTH) {
            return false;
        }
        return nickname.chars().noneMatch(Character::isISOControl);
    }

    private boolean nicknameInUse(MinecraftServer server, ServerPlayer target, String nickname) {
        String folded = nickname.toLowerCase(Locale.ROOT);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(target.getUUID())) {
                continue;
            }
            if (player.getScoreboardName().equalsIgnoreCase(nickname)
                || SocialPlayerLookup.displayName(player).equalsIgnoreCase(nickname)
                || state.nickname(player.getUUID()).map(value -> value.toLowerCase(Locale.ROOT).equals(folded)).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private static void applyNickname(ServerPlayer player, String nickname) {
        Component component = Component.literal(nickname);
        player.setCustomName(component);
        player.setCustomNameVisible(true);
    }

    private static void clearNickname(ServerPlayer player) {
        player.setCustomName(null);
        player.setCustomNameVisible(false);
    }

    @FunctionalInterface
    private interface ToggleHandler {
        int apply(CommandContext<CommandSourceStack> context, ServerPlayer target, Boolean enabled) throws CommandSyntaxException;
    }

    private record TargetAndMessage(ServerPlayer target, String message) {
    }
}
