package com.vantablack4.essentials.commands.info;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.vantablack4.essentials.EssentialsConfig;
import com.vantablack4.essentials.Messages;
import com.vantablack4.essentials.PermissionChecks;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.stats.Stats;

public final class InfoCommands {
    private static final String TARGET_ARGUMENT = "target";
    private static final String QUERY_ARGUMENT = "query";

    private final PermissionChecks permissions;
    private final InfoPlayerSessions sessions;
    private final StaticTextCommands staticTextCommands;

    public InfoCommands(EssentialsConfig config, PermissionChecks permissions) {
        this.permissions = permissions;
        this.sessions = new InfoPlayerSessions();
        this.staticTextCommands = new StaticTextCommands(config.configDirectory());
    }

    public void registerLifecycle() {
        sessions.register();
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerAll(dispatcher, this::playtimeCommand, "playtime", "eplaytime");
        registerAll(dispatcher, this::seenCommand, "seen", "eseen", "ealts", "alts");
        registerAll(dispatcher, this::whoisCommand, "whois", "ewhois");
        registerAll(dispatcher, this::realnameCommand, "realname", "erealname");
        staticTextCommands.register(dispatcher);
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

    private LiteralArgumentBuilder<CommandSourceStack> playtimeCommand(String name) {
        return Commands.literal(name)
            .executes(this::playtimeSelf)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.greedyString())
                .requires(permissions::admin)
                .suggests(this::suggestOnlinePlayers)
                .executes(this::playtimeTarget));
    }

    private LiteralArgumentBuilder<CommandSourceStack> seenCommand(String name) {
        return Commands.literal(name)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.greedyString())
                .suggests(this::suggestKnownPlayers)
                .executes(this::seen));
    }

    private LiteralArgumentBuilder<CommandSourceStack> whoisCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.greedyString())
                .suggests(this::suggestOnlinePlayers)
                .executes(this::whois));
    }

    private LiteralArgumentBuilder<CommandSourceStack> realnameCommand(String name) {
        return Commands.literal(name)
            .then(Commands.argument(QUERY_ARGUMENT, StringArgumentType.greedyString())
                .suggests(this::suggestOnlinePlayers)
                .executes(this::realname));
    }

    private int playtimeSelf(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        sendPlaytime(context.getSource(), player);
        return 1;
    }

    private int playtimeTarget(CommandContext<CommandSourceStack> context) {
        ServerPlayer target = resolveOnline(context, TARGET_ARGUMENT);
        if (target == null) {
            context.getSource().sendSystemMessage(Messages.error("Playtime currently supports online players only."));
            return 0;
        }
        sendPlaytime(context.getSource(), target);
        return 1;
    }

    private int seen(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        sessions.observe(server);
        String rawTarget = getString(context, TARGET_ARGUMENT);

        ServerPlayer online = InfoPlayerLookup.findOnline(server, rawTarget).orElse(null);
        if (online != null) {
            InfoPlayerSessions.SessionRecord record = sessions.markOnline(online);
            context.getSource().sendSystemMessage(Messages.header("Seen"));
            context.getSource().sendSystemMessage(Messages.line("Status", InfoPlayerLookup.listedName(online) + " is online"));
            context.getSource().sendSystemMessage(Messages.line("Tracked online since", InfoFormat.since(record.currentSessionStartedAt())));
            context.getSource().sendSystemMessage(Messages.line("UUID", online.getUUID().toString()));
            context.getSource().sendSystemMessage(Messages.line("Playtime", playerPlaytime(online)));
            sendIdentityScope(context.getSource());
            return 1;
        }

        return sessions.find(rawTarget)
            .map(record -> seenFromSession(context, record))
            .orElseGet(() -> seenKnownProfile(context, rawTarget));
    }

    private int seenFromSession(CommandContext<CommandSourceStack> context, InfoPlayerSessions.SessionRecord record) {
        context.getSource().sendSystemMessage(Messages.header("Seen"));
        context.getSource().sendSystemMessage(Messages.line("Status", record.displayName() + " (" + record.accountName() + ") is offline"));
        context.getSource().sendSystemMessage(Messages.line("Last seen", InfoFormat.since(record.lastSeenAt())));
        context.getSource().sendSystemMessage(Messages.line("UUID", record.uuid().toString()));
        sendIdentityScope(context.getSource());
        return 1;
    }

    private int seenKnownProfile(CommandContext<CommandSourceStack> context, String rawTarget) {
        return InfoPlayerLookup.findKnownProfile(context.getSource().getServer(), rawTarget)
            .map(profile -> {
                context.getSource().sendSystemMessage(Messages.header("Seen"));
                context.getSource().sendSystemMessage(Messages.line("Known Minecraft profile", profile.name() + " (" + profile.id() + ")"));
                context.getSource().sendSystemMessage(Messages.line("Local session history", "none in this server process"));
                sendIdentityScope(context.getSource());
                return 1;
            })
            .orElseGet(() -> {
                context.getSource().sendSystemMessage(Messages.error("No online, runtime-seen, or cached Minecraft profile matched: " + rawTarget));
                sendIdentityScope(context.getSource());
                return 0;
            });
    }

    private int whois(CommandContext<CommandSourceStack> context) {
        ServerPlayer target = resolveOnline(context, TARGET_ARGUMENT);
        if (target == null) {
            return 0;
        }

        MinecraftServer server = context.getSource().getServer();
        NameAndId nameAndId = target.nameAndId();
        context.getSource().sendSystemMessage(Messages.header("Whois " + target.getScoreboardName()));
        context.getSource().sendSystemMessage(Messages.line("Display", InfoPlayerLookup.displayName(target)));
        context.getSource().sendSystemMessage(Messages.line("UUID", target.getUUID().toString()));
        context.getSource().sendSystemMessage(Messages.line("Health", InfoFormat.oneDecimal(target.getHealth()) + "/" + InfoFormat.oneDecimal(target.getMaxHealth())));
        context.getSource().sendSystemMessage(Messages.line("Hunger", target.getFoodData().getFoodLevel() + "/20, saturation " + InfoFormat.oneDecimal(target.getFoodData().getSaturationLevel())));
        context.getSource().sendSystemMessage(Messages.line("Experience", target.totalExperience + " total, level " + target.experienceLevel));
        context.getSource().sendSystemMessage(Messages.line("Game mode", target.gameMode().getName()));
        context.getSource().sendSystemMessage(Messages.line("Location", target.level().dimension().identifier() + " " + target.getBlockX() + ", " + target.getBlockY() + ", " + target.getBlockZ()));
        context.getSource().sendSystemMessage(Messages.line("Fly", target.getAbilities().mayfly + " (flying " + target.getAbilities().flying + ")"));
        context.getSource().sendSystemMessage(Messages.line("Ping", target.connection.latency() + " ms"));
        context.getSource().sendSystemMessage(Messages.line("Address", target.getIpAddress()));
        context.getSource().sendSystemMessage(Messages.line("OP", Boolean.toString(server.getPlayerList().isOp(nameAndId))));
        context.getSource().sendSystemMessage(Messages.line("Whitelisted", Boolean.toString(server.getPlayerList().isWhiteListed(nameAndId))));
        context.getSource().sendSystemMessage(Messages.line("Local ban list", Boolean.toString(server.getPlayerList().getBans().isBanned(nameAndId))));
        context.getSource().sendSystemMessage(Messages.line("Playtime", playerPlaytime(target)));
        sendIdentityScope(context.getSource());
        return 1;
    }

    private int realname(CommandContext<CommandSourceStack> context) {
        String query = getString(context, QUERY_ARGUMENT);
        List<ServerPlayer> matches = context.getSource().getServer().getPlayerList().getPlayers().stream()
            .filter(player -> InfoPlayerLookup.displayNameMatches(player, query))
            .sorted(Comparator.comparing(ServerPlayer::getScoreboardName, String.CASE_INSENSITIVE_ORDER))
            .toList();

        if (matches.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("No online display name matched: " + query));
            sendIdentityScope(context.getSource());
            return 0;
        }

        context.getSource().sendSystemMessage(Messages.header("Realname"));
        for (ServerPlayer player : matches) {
            context.getSource().sendSystemMessage(Messages.line(InfoPlayerLookup.displayName(player), player.getScoreboardName()));
        }
        sendIdentityScope(context.getSource());
        return matches.size();
    }

    private void sendPlaytime(CommandSourceStack source, ServerPlayer player) {
        source.sendSystemMessage(Messages.line("Playtime", InfoPlayerLookup.listedName(player) + ": " + playerPlaytime(player)));
    }

    private String playerPlaytime(ServerPlayer player) {
        return InfoFormat.ticksAsDuration(player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME));
    }

    private ServerPlayer resolveOnline(CommandContext<CommandSourceStack> context, String argumentName) {
        String rawTarget = getString(context, argumentName);
        return InfoPlayerLookup.findOnline(context.getSource().getServer(), rawTarget)
            .orElseGet(() -> {
                context.getSource().sendSystemMessage(Messages.error("Online player not found: " + rawTarget));
                return null;
            });
    }

    private CompletableFuture<Suggestions> suggestOnlinePlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(InfoPlayerLookup.onlineSuggestions(context.getSource().getServer()), builder);
    }

    private CompletableFuture<Suggestions> suggestKnownPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        sessions.observe(context.getSource().getServer());
        return SharedSuggestionProvider.suggest(InfoPlayerLookup.knownSuggestions(context.getSource().getServer(), sessions), builder);
    }

    private void sendIdentityScope(CommandSourceStack source) {
        source.sendSystemMessage(Messages.line("Scope", "Minecraft runtime/profile-cache only; durable platform identity and character history are backend-owned."));
    }
}
