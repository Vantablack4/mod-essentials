package com.vantablack4.essentials.commands.admin;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
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

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
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

    private final PermissionChecks permissions;
    private final MuteStore muteStore;

    public AdminCommands(EssentialsConfig config, PermissionChecks permissions) {
        this.permissions = permissions;
        this.muteStore = new MuteStore(config.configDirectory().resolve("mutes.properties"));
    }

    public void registerLifecycle() {
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
                .executes(context -> ban(context, null, "Banned by an operator."))
                .then(Commands.argument(REASON_ARGUMENT, StringArgumentType.greedyString())
                    .executes(context -> ban(context, null, getString(context, REASON_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tempBanCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestKnownPlayers)
                .then(Commands.argument(DURATION_ARGUMENT, StringArgumentType.word())
                    .suggests(this::suggestDurations)
                    .executes(context -> tempBan(context, "Temporarily banned."))
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
                .executes(context -> banIp(context, null, "Banned by an operator."))
                .then(Commands.argument(REASON_ARGUMENT, StringArgumentType.greedyString())
                    .executes(context -> banIp(context, null, getString(context, REASON_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tempBanIpCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestPlayersAndIps)
                .then(Commands.argument(DURATION_ARGUMENT, StringArgumentType.word())
                    .suggests(this::suggestDurations)
                    .executes(context -> tempBanIp(context, "Temporarily banned."))
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
                .executes(context -> kick(context, "Kicked by an operator."))
                .then(Commands.argument(REASON_ARGUMENT, StringArgumentType.greedyString())
                    .executes(context -> kick(context, getString(context, REASON_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> kickAllCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .executes(context -> kickAll(context, "Kicked by an operator."))
            .then(Commands.argument(REASON_ARGUMENT, StringArgumentType.greedyString())
                .executes(context -> kickAll(context, getString(context, REASON_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> muteCommand(String name) {
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
                .suggests(this::suggestKnownPlayers)
                .executes(this::tpofflineUnsupported));
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

        boolean added = server.getPlayerList().getBans().add(new UserBanListEntry(profile, new Date(), sourceName(context), expires, reason));
        ServerPlayer online = server.getPlayerList().getPlayer(profile.id());
        if (online != null) {
            online.connection.disconnect(Component.literal(reason));
        }
        context.getSource().sendSystemMessage(Messages.success((added ? "Banned " : "Updated ban for ") + profile.name() + expirySuffix(expires)));
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
        context.getSource().sendSystemMessage(Messages.success("Unbanned " + profile.name()));
        return 1;
    }

    private int banIp(CommandContext<CommandSourceStack> context, Date expires, String reason) {
        MinecraftServer server = context.getSource().getServer();
        String ip = resolveIp(server, getString(context, TARGET_ARGUMENT)).orElse(null);
        if (ip == null) {
            context.getSource().sendSystemMessage(Messages.error("Invalid IP address or online player: " + getString(context, TARGET_ARGUMENT)));
            return 0;
        }

        boolean added = server.getPlayerList().getIpBans().add(new IpBanListEntry(ip, new Date(), sourceName(context), expires, reason));
        int kicked = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayersWithAddress(ip)) {
            player.connection.disconnect(Component.literal(reason));
            kicked++;
        }
        context.getSource().sendSystemMessage(Messages.success((added ? "Banned IP " : "Updated IP ban ") + ip + expirySuffix(expires) + " Kicked " + kicked + " players."));
        return 1;
    }

    private int unbanIp(CommandContext<CommandSourceStack> context) {
        String ip = normalizeIp(getString(context, TARGET_ARGUMENT)).orElse(null);
        if (ip == null) {
            context.getSource().sendSystemMessage(Messages.error("Invalid IP address: " + getString(context, TARGET_ARGUMENT)));
            return 0;
        }
        boolean removed = context.getSource().getServer().getPlayerList().getIpBans().remove(ip);
        context.getSource().sendSystemMessage(removed ? Messages.success("Unbanned IP " + ip) : Messages.error("IP is not banned: " + ip));
        return removed ? 1 : 0;
    }

    private int kick(CommandContext<CommandSourceStack> context, String reason) {
        ServerPlayer target = findOnline(context.getSource().getServer(), getString(context, TARGET_ARGUMENT)).orElse(null);
        if (target == null) {
            context.getSource().sendSystemMessage(Messages.error("Online player not found: " + getString(context, TARGET_ARGUMENT)));
            return 0;
        }
        target.connection.disconnect(Component.literal(reason));
        context.getSource().sendSystemMessage(Messages.success("Kicked " + target.getScoreboardName()));
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
        context.getSource().sendSystemMessage(Messages.success("Kicked " + kicked + " players."));
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
            context.getSource().sendSystemMessage(Messages.success("Unmuted " + profile.name()));
            return 1;
        }
        muteStore.put(profile.id(), profile.name(), -1L, sourceName(context), "Muted.");
        notifyMutedTarget(context.getSource().getServer(), profile.id(), "You have been muted.");
        context.getSource().sendSystemMessage(Messages.success("Muted " + profile.name() + " permanently."));
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
            context.getSource().sendSystemMessage(removed ? Messages.success("Unmuted " + profile.name()) : Messages.error("Player is not muted: " + profile.name()));
            return removed ? 1 : 0;
        }
        Long expiresAt = parseMuteExpiry(context, durationText);
        if (expiresAt == null) {
            return 0;
        }
        muteStore.put(profile.id(), profile.name(), expiresAt, sourceName(context), reason);
        notifyMutedTarget(context.getSource().getServer(), profile.id(), "You have been muted. Reason: " + reason);
        context.getSource().sendSystemMessage(Messages.success("Muted " + profile.name() + (expiresAt < 0L ? " permanently." : " until " + Instant.ofEpochMilli(expiresAt) + ".")));
        return 1;
    }

    private int tpofflineUnsupported(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSystemMessage(Messages.error(
            "/tpoffline is intentionally not implemented: offline player NBT mutation is unsafe here because Fabric/Minecraft 26 player data uses server-owned save/load and DataFixer paths. Use /tp when the player is online."
        ));
        return 0;
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
        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private CompletableFuture<Suggestions> suggestBannedIps(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(context.getSource().getServer().getPlayerList().getIpBans().getUserList(), builder);
    }

    private CompletableFuture<Suggestions> suggestDurations(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(Set.of("10m", "1h", "1d", "7d", "30d"), builder);
    }

    private CompletableFuture<Suggestions> suggestMuteDurations(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(Set.of("off", "10m", "1h", "1d", "7d", "perm"), builder);
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

    private Long parseMuteExpiry(CommandContext<CommandSourceStack> context, String durationText) {
        if (durationText.equalsIgnoreCase("perm") || durationText.equalsIgnoreCase("permanent")) {
            return -1L;
        }
        try {
            return System.currentTimeMillis() + parseDuration(durationText).toMillis();
        } catch (IllegalArgumentException | ArithmeticException exception) {
            context.getSource().sendSystemMessage(Messages.error("Invalid duration: " + durationText));
            return null;
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

    private static String expirySuffix(Date expires) {
        return expires == null ? "." : " until " + expires.toInstant() + ".";
    }

    private static String cleanInput(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").trim();
        }
        return trimmed;
    }
}
