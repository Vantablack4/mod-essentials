package com.vantablack4.essentials.commands.admin;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.vantablack4.essentials.EssentialsConfig;
import com.vantablack4.essentials.Messages;
import com.vantablack4.essentials.PermissionChecks;
import com.vantablack4.essentials.i18n.EssentialsMessageLookup;
import com.vantablack4.essentials.i18n.EssentialsXMessages;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.IpBanListEntry;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanListEntry;

public final class AdminCommands {
    private static final String TARGET_ARGUMENT = "target";
    private static final String REASON_ARGUMENT = "reason";
    private static final String DURATION_ARGUMENT = "duration";
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([smhdwMy])");
    private static final EssentialsMessageLookup UPSTREAM_MESSAGES = EssentialsXMessages.loadDefault().lookup(Locale.ROOT);

    private final PermissionChecks permissions;
    private final MuteStore muteStore;
    private final LogoutLocationStore logoutLocations;

    public AdminCommands(EssentialsConfig config, PermissionChecks permissions) {
        this.permissions = permissions;
        this.muteStore = new MuteStore(config.configDirectory().resolve("mutes.properties"));
        this.logoutLocations = new LogoutLocationStore(config.configDirectory().resolve("logout-locations.properties"));
    }

    public void registerLifecycle() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> logoutLocations.rememberIdentity(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> logoutLocations.record(handler.player));
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, parameters) -> allowMutedMessage(sender));
        ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register((message, source, parameters) -> {
            ServerPlayer sender = source.getPlayer();
            if (sender == null) {
                return true;
            }
            return allowMutedMessage(sender);
        });
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerAll(dispatcher, this::banCommand, "ban", "eban");
        registerAll(dispatcher, this::tempBanCommand, "tempban", "etempban");
        registerAll(dispatcher, this::unbanCommand, "unban", "eunban", "pardon", "epardon");
        registerAll(dispatcher, this::banIpCommand, "banip", "ebanip");
        registerAll(dispatcher, this::tempBanIpCommand, "tempbanip", "etempbanip");
        registerAll(dispatcher, this::unbanIpCommand, "unbanip", "eunbanip", "pardonip", "epardonip");
        registerAll(dispatcher, this::kickCommand, "kick", "ekick");
        registerAll(dispatcher, this::kickAllCommand, "kickall", "ekickall");
        registerAll(dispatcher, this::muteCommand, "mute", "emute", "silence", "esilence", "unmute", "eunmute");
        registerAll(dispatcher, this::tpofflineCommand, "tpoffline", "otp", "offlinetp", "tpoff", "etpoffline");
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

    private LiteralArgumentBuilder<CommandSourceStack> banCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestKnownPlayers)
                .executes(context -> ban(context, null, upstream("defaultBanReason", "The Ban Hammer has spoken!")))
                .then(Commands.argument(REASON_ARGUMENT, StringArgumentType.greedyString())
                    .executes(context -> ban(context, null, getString(context, REASON_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tempBanCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestKnownPlayers)
                .then(Commands.argument(DURATION_ARGUMENT, StringArgumentType.word())
                    .suggests(this::suggestDurations)
                    .executes(context -> tempBan(context, upstream("defaultBanReason", "The Ban Hammer has spoken!")))
                    .then(Commands.argument(REASON_ARGUMENT, StringArgumentType.greedyString())
                        .executes(context -> tempBan(context, getString(context, REASON_ARGUMENT))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> unbanCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestKnownPlayers)
                .executes(this::unban));
    }

    private LiteralArgumentBuilder<CommandSourceStack> banIpCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestPlayersAndIps)
                .executes(context -> banIp(context, null, upstream("defaultBanReason", "The Ban Hammer has spoken!")))
                .then(Commands.argument(REASON_ARGUMENT, StringArgumentType.greedyString())
                    .executes(context -> banIp(context, null, getString(context, REASON_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tempBanIpCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestPlayersAndIps)
                .then(Commands.argument(DURATION_ARGUMENT, StringArgumentType.word())
                    .suggests(this::suggestDurations)
                    .executes(context -> tempBanIp(context, upstream("defaultBanReason", "The Ban Hammer has spoken!")))
                    .then(Commands.argument(REASON_ARGUMENT, StringArgumentType.greedyString())
                        .executes(context -> tempBanIp(context, getString(context, REASON_ARGUMENT))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> unbanIpCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestBannedIps)
                .executes(this::unbanIp));
    }

    private LiteralArgumentBuilder<CommandSourceStack> kickCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestOnlinePlayers)
                .executes(context -> kick(context, upstream("kickDefault", "Kicked from server.")))
                .then(Commands.argument(REASON_ARGUMENT, StringArgumentType.greedyString())
                    .executes(context -> kick(context, getString(context, REASON_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> kickAllCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .executes(context -> kickAll(context, upstream("kickDefault", "Kicked from server.")))
            .then(Commands.argument(REASON_ARGUMENT, StringArgumentType.greedyString())
                .executes(context -> kickAll(context, getString(context, REASON_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> muteCommand(String name) {
        if (name.equalsIgnoreCase("unmute") || name.equalsIgnoreCase("eunmute")) {
            return Commands.literal(name).requires(permissions::admin)
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.word())
                    .suggests(this::suggestMutedPlayers)
                    .executes(this::unmute));
        }
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestKnownPlayers)
                .executes(this::toggleMute)
                .then(Commands.argument(DURATION_ARGUMENT, StringArgumentType.word())
                    .suggests(this::suggestMuteDurations)
                    .executes(context -> setMute(context, getString(context, DURATION_ARGUMENT), "Muted."))
                    .then(Commands.argument(REASON_ARGUMENT, StringArgumentType.greedyString())
                        .executes(context -> setMute(context, getString(context, DURATION_ARGUMENT), getString(context, REASON_ARGUMENT))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tpofflineCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.greedyString())
                .suggests(this::suggestLogoutPlayers)
                .executes(this::tpoffline));
    }

    private int tempBan(CommandContext<CommandSourceStack> context, String reason) {
        Date expires = expiresAtOrReport(context, getString(context, DURATION_ARGUMENT));
        return expires == null ? 0 : ban(context, expires, reason);
    }

    private int tempBanIp(CommandContext<CommandSourceStack> context, String reason) {
        Date expires = expiresAtOrReport(context, getString(context, DURATION_ARGUMENT));
        return expires == null ? 0 : banIp(context, expires, reason);
    }

    private int ban(CommandContext<CommandSourceStack> context, Date expires, String reason) {
        MinecraftServer server = context.getSource().getServer();
        String rawTarget = getString(context, TARGET_ARGUMENT);
        NameAndId profile = resolveProfile(server, rawTarget).orElse(null);
        if (profile == null) {
            context.getSource().sendSystemMessage(Messages.error("Player not found: " + rawTarget));
            return 0;
        }

        server.getPlayerList().getBans().add(new UserBanListEntry(profile, new Date(), sourceName(context), expires, reason));
        ServerPlayer online = server.getPlayerList().getPlayer(profile.id());
        if (online != null) {
            online.connection.disconnect(Component.literal(banDisconnectMessage(expires, reason, sourceName(context))));
        }
        context.getSource().sendSystemMessage(Messages.success(banBroadcastMessage(expires, sourceName(context), profile.name(), reason)));
        return 1;
    }

    private int unban(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        String rawTarget = getString(context, TARGET_ARGUMENT);
        NameAndId profile = resolveProfile(server, rawTarget).orElse(null);
        if (profile == null || !server.getPlayerList().getBans().isBanned(profile)) {
            context.getSource().sendSystemMessage(Messages.error("Player is not banned: " + rawTarget));
            return 0;
        }
        server.getPlayerList().getBans().remove(profile);
        context.getSource().sendSystemMessage(Messages.success(upstream("playerUnbanned", "Player {0} unbanned {1}", sourceName(context), profile.name())));
        return 1;
    }

    private int banIp(CommandContext<CommandSourceStack> context, Date expires, String reason) {
        MinecraftServer server = context.getSource().getServer();
        String ip = resolveIp(server, getString(context, TARGET_ARGUMENT)).orElse(null);
        if (ip == null) {
            context.getSource().sendSystemMessage(Messages.error("Invalid IP address or online player: " + getString(context, TARGET_ARGUMENT)));
            return 0;
        }

        server.getPlayerList().getIpBans().add(new IpBanListEntry(ip, new Date(), sourceName(context), expires, reason));
        int kicked = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayersWithAddress(ip)) {
            player.connection.disconnect(Component.literal(banDisconnectMessage(expires, reason, sourceName(context))));
            kicked++;
        }
        context.getSource().sendSystemMessage(Messages.success(ipBanBroadcastMessage(expires, sourceName(context), ip, reason) + " Kicked " + kicked + " players."));
        return 1;
    }

    private int unbanIp(CommandContext<CommandSourceStack> context) {
        String ip = resolveIp(context.getSource().getServer(), getString(context, TARGET_ARGUMENT)).orElse(null);
        if (ip == null) {
            context.getSource().sendSystemMessage(Messages.error("Invalid IP address: " + getString(context, TARGET_ARGUMENT)));
            return 0;
        }
        boolean removed = context.getSource().getServer().getPlayerList().getIpBans().remove(ip);
        context.getSource().sendSystemMessage(removed
            ? Messages.success(upstream("playerUnbanIpAddress", "Player {0} unbanned IP: {1}", sourceName(context), ip))
            : Messages.error("IP is not banned: " + ip));
        return removed ? 1 : 0;
    }

    private int kick(CommandContext<CommandSourceStack> context, String reason) {
        ServerPlayer target = findOnline(context.getSource().getServer(), getString(context, TARGET_ARGUMENT)).orElse(null);
        if (target == null) {
            context.getSource().sendSystemMessage(Messages.error("Online player not found: " + getString(context, TARGET_ARGUMENT)));
            return 0;
        }
        target.connection.disconnect(Component.literal(reason));
        context.getSource().sendSystemMessage(Messages.success(upstream("playerKicked", "Player {0} kicked {1} for {2}", sourceName(context), target.getScoreboardName(), reason)));
        return 1;
    }

    private int kickAll(CommandContext<CommandSourceStack> context, String reason) {
        ServerPlayer sender = context.getSource().getPlayer();
        int kicked = 0;
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            if (player == sender) {
                continue;
            }
            player.connection.disconnect(Component.literal(reason));
            kicked++;
        }
        context.getSource().sendSystemMessage(Messages.success(upstream("kickedAll", "Kicked all players from server.") + " (" + kicked + ")"));
        return kicked;
    }

    private int toggleMute(CommandContext<CommandSourceStack> context) {
        NameAndId profile = resolveProfile(context.getSource().getServer(), getString(context, TARGET_ARGUMENT)).orElse(null);
        if (profile == null) {
            context.getSource().sendSystemMessage(Messages.error("Player not found: " + getString(context, TARGET_ARGUMENT)));
            return 0;
        }
        if (muteStore.get(profile.id()).isPresent()) {
            muteStore.remove(profile.id());
            notifyMutedTarget(context.getSource().getServer(), profile.id(), upstream("playerUnmuted", "You have been unmuted."));
            context.getSource().sendSystemMessage(Messages.success(upstream("unmutedPlayer", "Player {0} unmuted.", profile.name())));
            return 1;
        }
        muteStore.put(profile.id(), profile.name(), -1L, sourceName(context), "Muted.");
        notifyMutedTarget(context.getSource().getServer(), profile.id(), upstream("playerMuted", "You have been muted!"));
        context.getSource().sendSystemMessage(Messages.success(upstream("mutedPlayer", "Player {0} muted.", profile.name())));
        return 1;
    }

    private int unmute(CommandContext<CommandSourceStack> context) {
        NameAndId profile = resolveProfile(context.getSource().getServer(), getString(context, TARGET_ARGUMENT)).orElse(null);
        if (profile == null) {
            context.getSource().sendSystemMessage(Messages.error("Player not found: " + getString(context, TARGET_ARGUMENT)));
            return 0;
        }
        boolean removed = muteStore.remove(profile.id());
        if (!removed) {
            context.getSource().sendSystemMessage(Messages.error(upstream("playerNotMuted", "{0} is not muted.", profile.name())));
            return 0;
        }
        notifyMutedTarget(context.getSource().getServer(), profile.id(), upstream("playerUnmuted", "You have been unmuted."));
        context.getSource().sendSystemMessage(Messages.success(upstream("unmutedPlayer", "Player {0} unmuted.", profile.name())));
        return 1;
    }

    private int setMute(CommandContext<CommandSourceStack> context, String durationText, String reason) {
        NameAndId profile = resolveProfile(context.getSource().getServer(), getString(context, TARGET_ARGUMENT)).orElse(null);
        if (profile == null) {
            context.getSource().sendSystemMessage(Messages.error("Player not found: " + getString(context, TARGET_ARGUMENT)));
            return 0;
        }
        if (durationText.equalsIgnoreCase("off") || durationText.equalsIgnoreCase("unmute")) {
            boolean removed = muteStore.remove(profile.id());
            if (removed) {
                notifyMutedTarget(context.getSource().getServer(), profile.id(), upstream("playerUnmuted", "You have been unmuted."));
            }
            context.getSource().sendSystemMessage(removed
                ? Messages.success(upstream("unmutedPlayer", "Player {0} unmuted.", profile.name()))
                : Messages.error(upstream("playerNotMuted", "{0} is not muted.", profile.name())));
            return removed ? 1 : 0;
        }
        Optional<Long> parsedExpiry = tryParseMuteExpiry(durationText);
        long expiresAt = parsedExpiry.orElse(-1L);
        String muteReason = parsedExpiry.isPresent() ? reason : combineReason(durationText, reason);
        boolean hasReason = muteReason != null && !muteReason.isBlank() && !muteReason.equals("Muted.");
        muteStore.put(profile.id(), profile.name(), expiresAt, sourceName(context), muteReason);
        notifyMutedTarget(context.getSource().getServer(), profile.id(), muteTargetMessage(expiresAt, durationText, muteReason, hasReason));
        context.getSource().sendSystemMessage(Messages.success(muteSourceMessage(profile.name(), expiresAt, durationText, muteReason, hasReason)));
        return 1;
    }

    private int tpoffline(CommandContext<CommandSourceStack> context) {
        ServerPlayer sender = context.getSource().getPlayer();
        if (sender == null) {
            context.getSource().sendSystemMessage(Messages.error("This command can only be run by a player."));
            return 0;
        }
        String target = getString(context, TARGET_ARGUMENT);
        Optional<LogoutLocationStore.LogoutRecord> record = logoutLocations.find(target);
        if (record.isEmpty()) {
            Optional<NameAndId> profile = resolveProfile(context.getSource().getServer(), target);
            if (profile.isPresent()) {
                record = logoutLocations.get(profile.get().id());
            }
        }
        if (record.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error(upstream("teleportOfflineUnknown", "Unable to find the last known position of {0}.", target)));
            return 0;
        }
        try {
            record.get().location().teleport(context.getSource().getServer(), sender);
        } catch (IllegalArgumentException exception) {
            context.getSource().sendSystemMessage(Messages.error(exception.getMessage()));
            return 0;
        }
        context.getSource().sendSystemMessage(Messages.success(upstream("teleporting", "Teleporting...")));
        return 1;
    }

    private void notifyMutedTarget(MinecraftServer server, UUID uuid, String message) {
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player != null) {
            player.sendSystemMessage(Messages.error(message));
        }
    }

    private boolean allowMutedMessage(ServerPlayer sender) {
        Optional<MuteStore.MuteRecord> mute = muteStore.get(sender.getUUID());
        if (mute.isEmpty()) {
            return true;
        }
        MuteStore.MuteRecord record = mute.get();
        String suffix = record.permanent() ? "" : " Expires: " + record.expiryText();
        sender.sendSystemMessage(Messages.error("You are muted. Reason: " + record.reason() + "." + suffix));
        return false;
    }

    private Optional<NameAndId> resolveProfile(MinecraftServer server, String rawTarget) {
        String target = cleanInput(rawTarget);
        if (target.isBlank()) {
            return Optional.empty();
        }
        Optional<ServerPlayer> online = findOnline(server, target);
        if (online.isPresent()) {
            return Optional.of(online.get().nameAndId());
        }
        try {
            Optional<NameAndId> byUuid = server.services().nameToIdCache().get(UUID.fromString(target));
            if (byUuid.isPresent()) {
                return byUuid;
            }
        } catch (IllegalArgumentException ignored) {
            // Not a UUID.
        }
        return server.services().nameToIdCache().get(target)
            .or(() -> Optional.of(NameAndId.createOffline(target)));
    }

    private Optional<ServerPlayer> findOnline(MinecraftServer server, String rawTarget) {
        String target = cleanInput(rawTarget);
        ServerPlayer byName = server.getPlayerList().getPlayer(target);
        if (byName != null) {
            return Optional.of(byName);
        }
        String folded = target.toLowerCase(Locale.ROOT);
        return server.getPlayerList().getPlayers().stream()
            .filter(player -> player.getScoreboardName().equalsIgnoreCase(target) || player.getDisplayName().getString().toLowerCase(Locale.ROOT).equals(folded))
            .findFirst();
    }

    private Optional<String> resolveIp(MinecraftServer server, String rawTarget) {
        Optional<ServerPlayer> online = findOnline(server, rawTarget);
        if (online.isPresent()) {
            return Optional.of(online.get().getIpAddress());
        }
        Optional<String> storedIp = logoutLocations.ipAddressFor(rawTarget);
        if (storedIp.isPresent()) {
            return storedIp;
        }
        return normalizeIp(rawTarget);
    }

    private Optional<String> normalizeIp(String rawTarget) {
        try {
            return Optional.of(InetAddress.getByName(cleanInput(rawTarget)).getHostAddress());
        } catch (UnknownHostException exception) {
            return Optional.empty();
        }
    }

    private CompletableFuture<Suggestions> suggestKnownPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> suggestions = onlineNames(context.getSource().getServer());
        for (MuteStore.MuteRecord record : muteStore.records()) {
            suggestions.add(record.name());
        }
        suggestions.addAll(logoutLocations.names());
        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private CompletableFuture<Suggestions> suggestMutedPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> suggestions = new LinkedHashSet<>();
        for (MuteStore.MuteRecord record : muteStore.records()) {
            suggestions.add(record.name());
        }
        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private CompletableFuture<Suggestions> suggestOnlinePlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(onlineNames(context.getSource().getServer()), builder);
    }

    private CompletableFuture<Suggestions> suggestPlayersAndIps(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> suggestions = onlineNames(context.getSource().getServer());
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            suggestions.add(player.getIpAddress());
        }
        suggestions.addAll(logoutLocations.names());
        suggestions.addAll(logoutLocations.ipAddresses());
        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private CompletableFuture<Suggestions> suggestLogoutPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> suggestions = onlineNames(context.getSource().getServer());
        suggestions.addAll(logoutLocations.names());
        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private CompletableFuture<Suggestions> suggestBannedIps(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(context.getSource().getServer().getPlayerList().getIpBans().getUserList(), builder);
    }

    private CompletableFuture<Suggestions> suggestDurations(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(Set.of("10m", "1h", "1d", "7d", "30d"), builder);
    }

    private CompletableFuture<Suggestions> suggestMuteDurations(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(Set.of("off", "unmute", "10m", "1h", "1d", "7d", "perm", "permanent"), builder);
    }

    private Set<String> onlineNames(MinecraftServer server) {
        Set<String> suggestions = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            suggestions.add(player.getScoreboardName());
        }
        return suggestions;
    }

    private Date expiresAtOrReport(CommandContext<CommandSourceStack> context, String durationText) {
        try {
            return new Date(System.currentTimeMillis() + parseDuration(durationText).toMillis());
        } catch (IllegalArgumentException | ArithmeticException exception) {
            context.getSource().sendSystemMessage(Messages.error("Invalid duration: " + durationText));
            return null;
        }
    }

    private Optional<Long> tryParseMuteExpiry(String durationText) {
        if (durationText.equalsIgnoreCase("perm") || durationText.equalsIgnoreCase("permanent")) {
            return Optional.of(-1L);
        }
        try {
            return Optional.of(System.currentTimeMillis() + parseDuration(durationText).toMillis());
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private Duration parseDuration(String raw) {
        String value = raw.trim();
        if (value.matches("\\d+")) {
            return Duration.ofSeconds(Long.parseLong(value));
        }
        Matcher matcher = DURATION_PART.matcher(value);
        long millis = 0L;
        int consumed = 0;
        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            millis = Math.addExact(millis, switch (matcher.group(2)) {
                case "s" -> Duration.ofSeconds(amount).toMillis();
                case "m" -> Duration.ofMinutes(amount).toMillis();
                case "h" -> Duration.ofHours(amount).toMillis();
                case "d" -> Duration.ofDays(amount).toMillis();
                case "w" -> Duration.ofDays(amount * 7L).toMillis();
                case "M" -> Duration.ofDays(amount * 30L).toMillis();
                case "y" -> Duration.ofDays(amount * 365L).toMillis();
                default -> 0L;
            });
            consumed += matcher.group(0).length();
        }
        if (millis <= 0L || consumed != value.length()) {
            throw new IllegalArgumentException("Invalid duration: " + raw);
        }
        return Duration.ofMillis(millis);
    }

    private static String sourceName(CommandContext<CommandSourceStack> context) {
        return context.getSource().getTextName();
    }

    private static String banDisconnectMessage(Date expires, String reason, String source) {
        if (expires == null) {
            return upstream("banFormat", "You have been banned:\n{0}", reason);
        }
        return upstream("tempBanned", "You have been temporarily banned for {0}:\n{2}", expires.toInstant(), source, reason);
    }

    private static String banBroadcastMessage(Date expires, String source, String target, String reason) {
        if (expires == null) {
            return upstream("playerBanned", "Player {0} banned {1} for: {2}", source, target, reason);
        }
        return upstream("playerTempBanned", "Player {0} temporarily banned {1} for {2}: {3}", source, target, expires.toInstant(), reason);
    }

    private static String ipBanBroadcastMessage(Date expires, String source, String ip, String reason) {
        if (expires == null) {
            return upstream("playerBanIpAddress", "Player {0} banned IP address {1} for: {2}", source, ip, reason);
        }
        return upstream("playerTempBanIpAddress", "Player {0} temporarily banned IP address {1} for {2}: {3}", source, ip, expires.toInstant(), reason);
    }

    private static String muteTargetMessage(long expiresAt, String durationText, String reason, boolean hasReason) {
        if (expiresAt < 0L) {
            return hasReason
                ? upstream("playerMutedReason", "You have been muted! Reason: {0}", reason)
                : upstream("playerMuted", "You have been muted!");
        }
        return hasReason
            ? upstream("playerMutedForReason", "You have been muted for {0}. Reason: {1}", durationText, reason)
            : upstream("playerMutedFor", "You have been muted for {0}.", durationText);
    }

    private static String muteSourceMessage(String target, long expiresAt, String durationText, String reason, boolean hasReason) {
        if (expiresAt < 0L) {
            return hasReason
                ? upstream("mutedPlayerReason", "Player {0} muted. Reason: {1}", target, reason)
                : upstream("mutedPlayer", "Player {0} muted.", target);
        }
        return hasReason
            ? upstream("mutedPlayerForReason", "Player {0} muted for {1}. Reason: {2}", target, durationText, reason)
            : upstream("mutedPlayerFor", "Player {0} muted for {1}.", target, durationText);
    }

    private static String combineReason(String firstWord, String rest) {
        if (rest == null || rest.isBlank() || rest.equals("Muted.")) {
            return firstWord;
        }
        return firstWord + " " + rest;
    }

    private static String upstream(String key, String fallback, Object... arguments) {
        return stripLegacyTags(UPSTREAM_MESSAGES.messageOrDefault(key, fallback, arguments));
    }

    private static String stripLegacyTags(String value) {
        return value.replaceAll("<[^>]+>", "").trim();
    }

    private static String cleanInput(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").trim();
        }
        return trimmed;
    }
}
