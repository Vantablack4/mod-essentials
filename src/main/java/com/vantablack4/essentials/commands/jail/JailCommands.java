package com.vantablack4.essentials.commands.jail;

import static com.mojang.brigadier.arguments.DoubleArgumentType.getDouble;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.vantablack4.essentials.EssentialsConfig;
import com.vantablack4.essentials.Messages;
import com.vantablack4.essentials.PermissionChecks;
import com.vantablack4.essentials.StoredLocation;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class JailCommands {
    private static final String NAME_ARGUMENT = "name";
    private static final String TARGET_ARGUMENT = "target";
    private static final String REASON_ARGUMENT = "reason";
    private static final String MIN_RADIUS_ARGUMENT = "minRadius";
    private static final String MAX_RADIUS_ARGUMENT = "maxRadius";
    private static final double JAIL_RADIUS = 6.0D;
    private static final int RANDOM_TELEPORT_ATTEMPTS = 64;

    private final PermissionChecks permissions;
    private final JailStateStore jailStore;
    private final RandomTeleportStore randomTeleportStore;
    private int jailTick;

    public JailCommands(EssentialsConfig config, PermissionChecks permissions) {
        this.permissions = permissions;
        this.jailStore = new JailStateStore(config.configDirectory().resolve("jails.properties"));
        this.randomTeleportStore = new RandomTeleportStore(config.configDirectory().resolve("random-teleport.properties"));
    }

    public void registerLifecycle() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            jailTick = (jailTick + 1) % 20;
            if (jailTick == 0) {
                enforceJails(server);
            }
        });
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerAll(dispatcher, this::setJailCommand, "setjail", "esetjail", "createjail", "ecreatejail");
        registerAll(dispatcher, this::deleteJailCommand, "deljail", "edeljail", "remjail", "eremjail", "rmjail", "ermjail");
        registerAll(dispatcher, this::toggleJailCommand, "togglejail", "etogglejail", "jail", "ejail", "tjail", "etjail", "unjail", "eunjail");
        registerAll(dispatcher, this::jailsCommand, "jails", "ejails");
        registerAll(dispatcher, this::jailedPlayersCommand, "jailedplayers", "ejailedplayers", "ejailed", "ejp");
        registerAll(dispatcher, this::tprCommand, "tpr", "etpr", "tprandom", "etprandom", "rtp", "ertp", "wild", "ewild");
        registerAll(dispatcher, this::settprCommand, "settpr", "esettpr", "settprandom", "esettprandom");
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

    private LiteralArgumentBuilder<CommandSourceStack> setJailCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes(this::setJail));
    }

    private LiteralArgumentBuilder<CommandSourceStack> deleteJailCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestJails)
                .executes(this::deleteJail));
    }

    private LiteralArgumentBuilder<CommandSourceStack> toggleJailCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestKnownPlayers)
                .executes(context -> toggleJail(context, null, "Jailed"))
                .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                    .suggests(this::suggestJails)
                    .executes(context -> toggleJail(context, getString(context, NAME_ARGUMENT), "Jailed"))
                    .then(Commands.argument(REASON_ARGUMENT, StringArgumentType.greedyString())
                        .executes(context -> toggleJail(context, getString(context, NAME_ARGUMENT), getString(context, REASON_ARGUMENT))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> jailsCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .executes(this::jails);
    }

    private LiteralArgumentBuilder<CommandSourceStack> jailedPlayersCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .executes(this::jailedPlayers);
    }

    private LiteralArgumentBuilder<CommandSourceStack> tprCommand(String name) {
        return Commands.literal(name)
            .executes(context -> tpr(context, context.getSource().getPlayerOrException()))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.word())
                .requires(permissions::admin)
                .suggests(this::suggestOnlinePlayers)
                .executes(context -> {
                    ServerPlayer target = findOnline(context.getSource().getServer(), getString(context, TARGET_ARGUMENT)).orElse(null);
                    if (target == null) {
                        context.getSource().sendSystemMessage(Messages.error("Online player not found: " + getString(context, TARGET_ARGUMENT)));
                        return 0;
                    }
                    return tpr(context, target);
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> settprCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .executes(this::showTpr)
            .then(Commands.literal("clear").executes(this::clearTpr))
            .then(Commands.argument(MAX_RADIUS_ARGUMENT, DoubleArgumentType.doubleArg(1.0D, 30_000_000.0D))
                .executes(context -> setTpr(context, 0.0D, getDouble(context, MAX_RADIUS_ARGUMENT)))
                .then(Commands.argument(MIN_RADIUS_ARGUMENT, DoubleArgumentType.doubleArg(0.0D, 30_000_000.0D))
                    .executes(context -> setTpr(context, getDouble(context, MIN_RADIUS_ARGUMENT), getDouble(context, MAX_RADIUS_ARGUMENT)))));
    }

    private int setJail(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String name = JailStateStore.normalize(getString(context, NAME_ARGUMENT));
        if (name.isBlank()) {
            context.getSource().sendSystemMessage(Messages.error("Invalid jail name."));
            return 0;
        }
        jailStore.setJail(name, StoredLocation.capture(player));
        context.getSource().sendSystemMessage(Messages.success("Jail set: " + name));
        return 1;
    }

    private int deleteJail(CommandContext<CommandSourceStack> context) {
        String name = getString(context, NAME_ARGUMENT);
        boolean removed = jailStore.deleteJail(name);
        context.getSource().sendSystemMessage(removed ? Messages.success("Jail deleted: " + name) : Messages.error("Jail not found: " + name));
        return removed ? 1 : 0;
    }

    private int toggleJail(CommandContext<CommandSourceStack> context, String requestedJail, String reason) {
        MinecraftServer server = context.getSource().getServer();
        NameAndId profile = resolveProfile(server, getString(context, TARGET_ARGUMENT)).orElse(null);
        if (profile == null) {
            context.getSource().sendSystemMessage(Messages.error("Player not found: " + getString(context, TARGET_ARGUMENT)));
            return 0;
        }

        Optional<JailStateStore.JailedPlayer> existing = jailStore.jailed(profile.id());
        if (existing.isPresent()) {
            jailStore.release(profile.id());
            ServerPlayer online = server.getPlayerList().getPlayer(profile.id());
            if (online != null) {
                online.sendSystemMessage(Messages.success("You have been released from jail."));
            }
            context.getSource().sendSystemMessage(Messages.success("Released " + profile.name() + " from jail."));
            return 1;
        }

        String jailName = selectJail(requestedJail).orElse(null);
        if (jailName == null) {
            context.getSource().sendSystemMessage(Messages.error("Specify a jail name. Existing jails: " + String.join(", ", jailStore.jails())));
            return 0;
        }
        StoredLocation location = jailStore.jail(jailName).orElse(null);
        if (location == null) {
            context.getSource().sendSystemMessage(Messages.error("Jail not found: " + jailName));
            return 0;
        }
        jailStore.jail(profile.id(), profile.name(), jailName, context.getSource().getTextName(), reason);
        ServerPlayer online = server.getPlayerList().getPlayer(profile.id());
        if (online != null) {
            location.teleport(server, online);
            online.sendSystemMessage(Messages.error("You have been jailed: " + reason));
        }
        context.getSource().sendSystemMessage(Messages.success("Jailed " + profile.name() + " in " + jailName + (online == null ? " (will apply when online)." : ".")));
        return 1;
    }

    private int jails(CommandContext<CommandSourceStack> context) {
        List<String> jails = jailStore.jails();
        if (jails.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("No jails are set."));
            return 0;
        }
        context.getSource().sendSystemMessage(Messages.header("Jails"));
        for (String jail : jails) {
            context.getSource().sendSystemMessage(Messages.line(jail, jailStore.jail(jail).map(StoredLocation::display).orElse("invalid location")));
        }
        return jails.size();
    }

    private int jailedPlayers(CommandContext<CommandSourceStack> context) {
        List<JailStateStore.JailedPlayer> players = jailStore.jailedPlayers();
        if (players.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.success("No players are jailed."));
            return 1;
        }
        context.getSource().sendSystemMessage(Messages.header("Jailed players"));
        for (JailStateStore.JailedPlayer player : players) {
            context.getSource().sendSystemMessage(Messages.line(player.name(), player.jail() + " - " + player.reason()));
        }
        return players.size();
    }

    private int tpr(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        Optional<RandomTeleportStore.Area> area = randomTeleportStore.area();
        if (area.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Random teleport area is not set. Use /settpr <radius>."));
            return 0;
        }
        ServerLevel level = context.getSource().getServer().getLevel(area.get().dimension());
        if (level == null) {
            context.getSource().sendSystemMessage(Messages.error("Random teleport dimension is not loaded: " + area.get().dimension().identifier()));
            return 0;
        }
        Optional<Vec3> destination = findRandomSafeLocation(level, target, area.get());
        if (destination.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("No safe random teleport location found after " + RANDOM_TELEPORT_ATTEMPTS + " attempts."));
            return 0;
        }
        Vec3 position = destination.get();
        target.teleportTo(level, position.x, position.y, position.z, Set.<Relative>of(), target.getYRot(), target.getXRot(), false);
        target.sendSystemMessage(Messages.success("Randomly teleported."));
        if (context.getSource().getPlayer() != target) {
            context.getSource().sendSystemMessage(Messages.success("Randomly teleported " + target.getScoreboardName() + "."));
        }
        return 1;
    }

    private int setTpr(CommandContext<CommandSourceStack> context, double minRadius, double maxRadius) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (minRadius > maxRadius) {
            context.getSource().sendSystemMessage(Messages.error("Minimum radius cannot exceed maximum radius."));
            return 0;
        }
        RandomTeleportStore.Area area = new RandomTeleportStore.Area(
            player.level().dimension(),
            player.getX(),
            player.getZ(),
            minRadius,
            maxRadius
        );
        randomTeleportStore.set(area);
        context.getSource().sendSystemMessage(Messages.success("Random teleport area set in " + area.dimension().identifier() + " center "
            + block(area.centerX()) + ", " + block(area.centerZ()) + " radius " + area.minRadius() + "-" + area.maxRadius()));
        return 1;
    }

    private int showTpr(CommandContext<CommandSourceStack> context) {
        Optional<RandomTeleportStore.Area> area = randomTeleportStore.area();
        if (area.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Random teleport area is not set."));
            return 0;
        }
        RandomTeleportStore.Area value = area.get();
        context.getSource().sendSystemMessage(Messages.header("Random teleport"));
        context.getSource().sendSystemMessage(Messages.line("Dimension", value.dimension().identifier().toString()));
        context.getSource().sendSystemMessage(Messages.line("Center", block(value.centerX()) + ", " + block(value.centerZ())));
        context.getSource().sendSystemMessage(Messages.line("Radius", value.minRadius() + "-" + value.maxRadius()));
        return 1;
    }

    private int clearTpr(CommandContext<CommandSourceStack> context) {
        boolean removed = randomTeleportStore.clear();
        context.getSource().sendSystemMessage(removed ? Messages.success("Random teleport area cleared.") : Messages.error("Random teleport area was not set."));
        return removed ? 1 : 0;
    }

    private void enforceJails(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Optional<JailStateStore.JailedPlayer> jailed = jailStore.jailed(player.getUUID());
            if (jailed.isEmpty()) {
                continue;
            }
            Optional<StoredLocation> location = jailStore.jail(jailed.get().jail());
            if (location.isEmpty()) {
                continue;
            }
            if (outsideJail(player, location.get())) {
                location.get().teleport(server, player);
                player.sendSystemMessage(Messages.error("You cannot leave jail."));
            }
        }
    }

    private boolean outsideJail(ServerPlayer player, StoredLocation jail) {
        if (!player.level().dimension().equals(jail.dimension())) {
            return true;
        }
        return player.position().distanceToSqr(jail.x(), jail.y(), jail.z()) > JAIL_RADIUS * JAIL_RADIUS;
    }

    private Optional<String> selectJail(String requestedJail) {
        if (requestedJail != null && !requestedJail.isBlank()) {
            return Optional.of(JailStateStore.normalize(requestedJail));
        }
        List<String> jails = jailStore.jails();
        return jails.size() == 1 ? Optional.of(jails.getFirst()) : Optional.empty();
    }

    private Optional<Vec3> findRandomSafeLocation(ServerLevel level, ServerPlayer player, RandomTeleportStore.Area area) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double span = Math.max(0.0D, area.maxRadius() - area.minRadius());
        for (int attempt = 0; attempt < RANDOM_TELEPORT_ATTEMPTS; attempt++) {
            double radius = area.minRadius() + random.nextDouble() * span;
            double angle = random.nextDouble(0.0D, Math.PI * 2.0D);
            int x = block(area.centerX() + Math.cos(angle) * radius);
            int z = block(area.centerZ() + Math.sin(angle) * radius);
            BlockPos column = new BlockPos(x, level.getMinY(), z);
            if (!level.getWorldBorder().isWithinBounds(column)) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            if (!Level.isInSpawnableBounds(feet) || !level.getWorldBorder().isWithinBounds(feet)) {
                continue;
            }
            Vec3 position = new Vec3(x + 0.5D, y, z + 0.5D);
            if (safeForTeleport(level, player, feet, position)) {
                return Optional.of(position);
            }
        }
        return Optional.empty();
    }

    private boolean safeForTeleport(ServerLevel level, ServerPlayer player, BlockPos feet, Vec3 position) {
        BlockState floor = level.getBlockState(feet.below());
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(feet.above());
        if (!floor.blocksMotion() || !floor.getFluidState().isEmpty() || !feetState.getFluidState().isEmpty() || !headState.getFluidState().isEmpty()) {
            return false;
        }
        if (!feetState.getCollisionShape(level, feet).isEmpty() || !headState.getCollisionShape(level, feet.above()).isEmpty()) {
            return false;
        }
        AABB movedBox = player.getBoundingBox().move(position.x - player.getX(), position.y - player.getY(), position.z - player.getZ());
        return level.noCollision(player, movedBox);
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

    private CompletableFuture<Suggestions> suggestJails(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(jailStore.jails(), builder);
    }

    private CompletableFuture<Suggestions> suggestOnlinePlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(onlineNames(context.getSource().getServer()), builder);
    }

    private CompletableFuture<Suggestions> suggestKnownPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> suggestions = onlineNames(context.getSource().getServer());
        for (JailStateStore.JailedPlayer player : jailStore.jailedPlayers()) {
            suggestions.add(player.name());
        }
        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private Set<String> onlineNames(MinecraftServer server) {
        Set<String> suggestions = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            suggestions.add(player.getScoreboardName());
        }
        return suggestions;
    }

    private static int block(double value) {
        return (int) Math.floor(value);
    }

    private static String cleanInput(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").trim();
        }
        return trimmed;
    }
}
