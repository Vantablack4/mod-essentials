package com.vantablack4.essentials.commands.world;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.vantablack4.essentials.Messages;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class WorldUtilityCommands {
    private static final String TARGET_ARGUMENT = "target";
    private static final String POWER_ARGUMENT = "power";
    private static final String TYPE_ARGUMENT = "type";
    private static final String AMOUNT_ARGUMENT = "amount";
    private static final String RADIUS_ARGUMENT = "radius";
    private static final String META_ARGUMENT = "meta";
    private static final String LINE_ARGUMENT = "line";
    private static final String TEXT_ARGUMENT = "text";
    private static final String STATE_ARGUMENT = "state";
    private static final int DEFAULT_RADIUS = 64;

    private WorldUtilityCommands() {
    }

    public static void register(
        CommandDispatcher<CommandSourceStack> dispatcher,
        CommandBuildContext registryAccess,
        Predicate<CommandSourceStack> admin
    ) {
        registerAll(dispatcher, name -> lightningCommand(name, admin), "lightning", "elightning", "shock", "eshock", "smite", "esmite", "strike", "estrike", "thor", "ethor");
        registerAll(dispatcher, name -> antiochCommand(name, admin), "antioch", "eantioch", "grenade", "egrenade", "tnt", "etnt");
        registerAll(dispatcher, name -> nukeCommand(name, admin), "nuke", "enuke");
        registerAll(dispatcher, name -> fireballCommand(name, admin), "fireball", "efireball", "fireentity", "efireentity", "fireskull", "efireskull");
        registerAll(dispatcher, name -> launchEntityCommand(name, admin, EntityType.BEE, "Beezooka"), "beezooka", "ebeezooka", "beecannon", "ebeecannon");
        registerAll(dispatcher, name -> launchEntityCommand(name, admin, EntityType.CAT, "Kitty cannon"), "kittycannon", "ekittycannon");
        registerAll(dispatcher, name -> breakCommand(name, admin), "break", "ebreak");
        registerAll(dispatcher, name -> iceCommand(name, admin), "ice", "eice", "freeze", "efreeze");
        registerAll(dispatcher, name -> treeCommand(name, admin, false), "tree", "etree");
        registerAll(dispatcher, name -> treeCommand(name, admin, true), "bigtree", "ebigtree", "largetree", "elargetree");
        registerAll(dispatcher, name -> spawnMobCommand(name, registryAccess, admin), "spawnmob", "mob", "emob", "spawnentity", "espawnentity", "espawnmob");
        registerAll(dispatcher, name -> spawnerCommand(name, registryAccess, admin), "spawner", "espawner", "changems", "echangems", "mobspawner", "emobspawner");
        registerAll(dispatcher, name -> editSignCommand(name, admin), "editsign", "sign", "esign", "eeditsign");
        registerAll(dispatcher, name -> removeCommand(name, admin), "remove", "eremove", "butcher", "ebutcher", "killall", "ekillall", "mobkill", "emobkill");
        registerAll(dispatcher, name -> fireworkCommand(name, admin), "firework", "efirework");
        registerAll(dispatcher, name -> vanishCommand(name, admin), "vanish", "v", "ev", "evanish");
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

    private static LiteralArgumentBuilder<CommandSourceStack> lightningCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name).requires(admin)
            .executes(context -> lightningAtLook(context, 1))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(WorldUtilityCommands::suggestPlayers)
                .executes(context -> lightningAtTarget(context, 1))
                .then(Commands.argument(POWER_ARGUMENT, IntegerArgumentType.integer(1, 64))
                    .executes(context -> lightningAtTarget(context, getInteger(context, POWER_ARGUMENT)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> explosionCommand(String name, Predicate<CommandSourceStack> admin, float defaultPower) {
        return Commands.literal(name).requires(admin)
            .executes(context -> explodeAtLook(context, defaultPower))
            .then(Commands.argument(POWER_ARGUMENT, IntegerArgumentType.integer(1, 64))
                .executes(context -> explodeAtLook(context, getInteger(context, POWER_ARGUMENT))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> antiochCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name).requires(admin)
            .executes(context -> antioch(context, 1))
            .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer(1, 16))
                .executes(context -> antioch(context, getInteger(context, AMOUNT_ARGUMENT))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> nukeCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name).requires(admin)
            .executes(context -> nuke(context, 8))
            .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer(1, 64))
                .executes(context -> nuke(context, getInteger(context, AMOUNT_ARGUMENT))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> fireballCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name).requires(admin)
            .executes(context -> fireball(context, 1))
            .then(Commands.argument(POWER_ARGUMENT, IntegerArgumentType.integer(1, 8))
                .executes(context -> fireball(context, getInteger(context, POWER_ARGUMENT))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> launchEntityCommand(
        String name,
        Predicate<CommandSourceStack> admin,
        EntityType<?> type,
        String label
    ) {
        return Commands.literal(name).requires(admin)
            .executes(context -> launchEntity(context, type, label));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> breakCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name).requires(admin)
            .executes(WorldUtilityCommands::breakBlock);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> iceCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name).requires(admin)
            .executes(context -> freeze(context, context.getSource().getPlayerOrException()))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(WorldUtilityCommands::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolvePlayer(context);
                    return target == null ? 0 : freeze(context, target);
                }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> treeCommand(String name, Predicate<CommandSourceStack> admin, boolean large) {
        return Commands.literal(name).requires(admin)
            .executes(context -> tree(context, large))
            .then(Commands.argument(TYPE_ARGUMENT, StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("tree", "birch", "redwood", "jungle", "darkoak"), builder))
                .executes(context -> tree(context, large)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> spawnMobCommand(
        String name,
        CommandBuildContext registryAccess,
        Predicate<CommandSourceStack> admin
    ) {
        return Commands.literal(name).requires(admin)
            .then(Commands.argument(TYPE_ARGUMENT, ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE))
                .executes(context -> spawnMob(context, 1, null))
                .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer(1, 64))
                    .executes(context -> spawnMob(context, getInteger(context, AMOUNT_ARGUMENT), null))
                    .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                        .suggests(WorldUtilityCommands::suggestPlayers)
                        .executes(context -> spawnMob(context, getInteger(context, AMOUNT_ARGUMENT), resolvePlayer(context))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> spawnerCommand(
        String name,
        CommandBuildContext registryAccess,
        Predicate<CommandSourceStack> admin
    ) {
        return Commands.literal(name).requires(admin)
            .then(Commands.argument(TYPE_ARGUMENT, ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE))
                .executes(WorldUtilityCommands::setSpawner));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> editSignCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name).requires(admin)
            .then(Commands.literal("set")
                .then(Commands.argument(LINE_ARGUMENT, IntegerArgumentType.integer(1, SignText.LINES))
                    .then(Commands.argument(TEXT_ARGUMENT, StringArgumentType.greedyString())
                        .executes(context -> setSignLine(context, getInteger(context, LINE_ARGUMENT), getString(context, TEXT_ARGUMENT))))))
            .then(Commands.literal("clear")
                .executes(context -> clearSign(context, null))
                .then(Commands.argument(LINE_ARGUMENT, IntegerArgumentType.integer(1, SignText.LINES))
                    .executes(context -> clearSign(context, getInteger(context, LINE_ARGUMENT)))))
            .then(Commands.argument(LINE_ARGUMENT, IntegerArgumentType.integer(1, SignText.LINES))
                .then(Commands.argument(TEXT_ARGUMENT, StringArgumentType.greedyString())
                    .executes(context -> setSignLine(context, getInteger(context, LINE_ARGUMENT), getString(context, TEXT_ARGUMENT)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> removeCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name).requires(admin)
            .then(Commands.argument(TYPE_ARGUMENT, StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("all", "mobs", "monsters", "animals", "drops", "items"), builder))
                .executes(context -> remove(context, getString(context, TYPE_ARGUMENT), DEFAULT_RADIUS))
                .then(Commands.argument(RADIUS_ARGUMENT, IntegerArgumentType.integer(1, 1024))
                    .executes(context -> remove(context, getString(context, TYPE_ARGUMENT), getInteger(context, RADIUS_ARGUMENT)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> fireworkCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name).requires(admin)
            .executes(context -> setFirework(context, "color:red shape:ball power:1"))
            .then(Commands.literal("clear")
                .executes(WorldUtilityCommands::clearFirework))
            .then(Commands.literal("power")
                .then(Commands.argument(POWER_ARGUMENT, IntegerArgumentType.integer(0, 4))
                    .executes(context -> setFireworkPower(context, getInteger(context, POWER_ARGUMENT)))))
            .then(Commands.literal("p")
                .then(Commands.argument(POWER_ARGUMENT, IntegerArgumentType.integer(0, 4))
                    .executes(context -> setFireworkPower(context, getInteger(context, POWER_ARGUMENT)))))
            .then(Commands.literal("fire")
                .executes(context -> launchFireworks(context, 1))
                .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer(1, 64))
                    .executes(context -> launchFireworks(context, getInteger(context, AMOUNT_ARGUMENT)))))
            .then(Commands.literal("launch")
                .executes(context -> launchFireworks(context, 1))
                .then(Commands.argument(AMOUNT_ARGUMENT, IntegerArgumentType.integer(1, 64))
                    .executes(context -> launchFireworks(context, getInteger(context, AMOUNT_ARGUMENT)))))
            .then(Commands.argument(META_ARGUMENT, StringArgumentType.greedyString())
                .suggests(WorldUtilityCommands::suggestFireworkMeta)
                .executes(context -> setFirework(context, getString(context, META_ARGUMENT))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> vanishCommand(String name, Predicate<CommandSourceStack> admin) {
        return Commands.literal(name).requires(admin)
            .executes(context -> vanish(context, context.getSource().getPlayerOrException(), null))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(WorldUtilityCommands::suggestPlayers)
                .executes(context -> vanish(context, resolvePlayer(context), null))
                .then(Commands.argument(STATE_ARGUMENT, StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("on", "off", "true", "false", "enable", "disable"), builder))
                    .executes(context -> vanish(context, resolvePlayer(context), parseState(getString(context, STATE_ARGUMENT))))));
    }


    private static int lightningAtLook(CommandContext<CommandSourceStack> context, int power) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Vec3 position = lookedPosition(player);
        for (int index = 0; index < power; index++) {
            spawnLightning((ServerLevel) player.level(), position, player);
        }
        context.getSource().sendSystemMessage(Messages.success("Lightning struck."));
        return power;
    }

    private static int lightningAtTarget(CommandContext<CommandSourceStack> context, int power) {
        ServerPlayer target = resolvePlayer(context);
        if (target == null) {
            return 0;
        }
        for (int index = 0; index < power; index++) {
            spawnLightning((ServerLevel) target.level(), target.position(), target);
        }
        context.getSource().sendSystemMessage(Messages.success("Lightning struck " + target.getDisplayName().getString() + "."));
        return power;
    }

    private static int explodeAtLook(CommandContext<CommandSourceStack> context, float power) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Vec3 position = lookedPosition(player);
        ((ServerLevel) player.level()).explode(player, position.x, position.y, position.z, power, Level.ExplosionInteraction.TNT);
        context.getSource().sendSystemMessage(Messages.success("Explosion created."));
        return 1;
    }

    private static int antioch(CommandContext<CommandSourceStack> context, int amount) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        Vec3 direction = player.getLookAngle().normalize();
        Vec3 position = player.getEyePosition().add(direction.scale(1.5D));
        for (int index = 0; index < amount; index++) {
            PrimedTnt tnt = new PrimedTnt(level, position.x, position.y, position.z, player);
            tnt.setFuse(60);
            tnt.setDeltaMovement(direction.scale(0.9D).add(0.0D, 0.25D + index * 0.02D, 0.0D));
            level.addFreshEntity(tnt);
        }
        context.getSource().sendSystemMessage(Messages.success("Holy hand grenade thrown."));
        return amount;
    }

    private static int nuke(CommandContext<CommandSourceStack> context, int amount) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        Vec3 target = lookedPosition(player);
        for (int index = 0; index < amount; index++) {
            double offsetX = (level.getRandom().nextDouble() - 0.5D) * 18.0D;
            double offsetZ = (level.getRandom().nextDouble() - 0.5D) * 18.0D;
            PrimedTnt tnt = new PrimedTnt(level, target.x + offsetX, target.y + 24.0D + level.getRandom().nextDouble() * 10.0D, target.z + offsetZ, player);
            tnt.setFuse(40 + level.getRandom().nextInt(40));
            tnt.setDeltaMovement(-offsetX * 0.01D, -0.35D, -offsetZ * 0.01D);
            level.addFreshEntity(tnt);
        }
        context.getSource().sendSystemMessage(Messages.success("Nuke launched."));
        return amount;
    }

    private static int fireball(CommandContext<CommandSourceStack> context, int power) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Vec3 direction = player.getLookAngle().normalize();
        LargeFireball fireball = new LargeFireball(player.level(), player, direction.scale(Math.max(0.1D, power)), power);
        Vec3 position = player.getEyePosition().add(direction.scale(2.0D));
        fireball.setPos(position);
        ((ServerLevel) player.level()).addFreshEntity(fireball);
        context.getSource().sendSystemMessage(Messages.success("Fireball launched."));
        return 1;
    }

    private static int launchEntity(CommandContext<CommandSourceStack> context, EntityType<?> type, String label) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Entity entity = type.create((ServerLevel) player.level(), EntitySpawnReason.COMMAND);
        if (entity == null) {
            context.getSource().sendSystemMessage(Messages.error("Unable to create entity: " + EntityType.getKey(type)));
            return 0;
        }
        Vec3 direction = player.getLookAngle().normalize();
        entity.setPos(player.getEyePosition().add(direction.scale(1.5D)));
        entity.setDeltaMovement(direction.scale(1.5D));
        ((ServerLevel) player.level()).addFreshEntity(entity);
        context.getSource().sendSystemMessage(Messages.success(label + " launched."));
        return 1;
    }

    private static int breakBlock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockHitResult hit = lookedBlock(player);
        ServerLevel level = (ServerLevel) player.level();
        if (level.isEmptyBlock(hit.getBlockPos())) {
            player.sendSystemMessage(Messages.error("No breakable block in sight."));
            return 0;
        }
        level.destroyBlock(hit.getBlockPos(), true, player, 512);
        player.sendSystemMessage(Messages.success("Block broken."));
        return 1;
    }

    private static int freeze(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        target.setTicksFrozen(target.getTicksRequiredToFreeze() + 200);
        context.getSource().sendSystemMessage(Messages.success(target.getDisplayName().getString() + " frozen."));
        return 1;
    }

    private static int tree(CommandContext<CommandSourceStack> context, boolean large) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        BlockHitResult hit = lookedBlock(player);
        BlockPos base = hit.getBlockPos().relative(hit.getDirection());
        ResourceKey<ConfiguredFeature<?, ?>> featureKey = treeFeature(optionalString(context, TYPE_ARGUMENT, "tree"), large);
        ConfiguredFeature<?, ?> feature = level.registryAccess()
            .lookupOrThrow(Registries.CONFIGURED_FEATURE)
            .getValue(featureKey);
        if (feature == null || !feature.place(level, level.getChunkSource().getGenerator(), level.getRandom(), base)) {
            player.sendSystemMessage(Messages.error("Unable to place tree here."));
            return 0;
        }
        player.sendSystemMessage(Messages.success((large ? "Large tree" : "Tree") + " placed."));
        return 1;
    }

    private static int spawnMob(CommandContext<CommandSourceStack> context, int amount, ServerPlayer target) throws CommandSyntaxException {
        EntityType<?> type = ResourceArgument.getSummonableEntityType(context, TYPE_ARGUMENT).value();
        ServerPlayer sourcePlayer = context.getSource().getPlayerOrException();
        ServerPlayer destination = target == null ? sourcePlayer : target;
        ServerLevel level = (ServerLevel) destination.level();
        BlockPos pos = destination.blockPosition().relative(destination.getDirection(), 2);
        int spawned = 0;
        for (int index = 0; index < amount; index++) {
            Entity entity = type.spawn(level, pos, EntitySpawnReason.COMMAND);
            if (entity != null) {
                spawned++;
            }
        }
        context.getSource().sendSystemMessage(Messages.success("Spawned " + spawned + " x " + EntityType.getKey(type) + "."));
        return spawned;
    }

    private static int setSpawner(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        EntityType<?> type = ResourceArgument.getSummonableEntityType(context, TYPE_ARGUMENT).value();
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = lookedBlock(player).getBlockPos();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof SpawnerBlockEntity spawner)) {
            player.sendSystemMessage(Messages.error("No mob spawner in sight."));
            return 0;
        }
        spawner.setEntityId(type, level.getRandom());
        blockEntity.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        player.sendSystemMessage(Messages.success("Spawner changed to " + EntityType.getKey(type) + "."));
        return 1;
    }

    private static int setSignLine(CommandContext<CommandSourceStack> context, int line, String text) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = lookedBlock(player).getBlockPos();
        SignBlockEntity sign = signAt(level, pos, player);
        if (sign == null) {
            return 0;
        }
        boolean front = sign.isFacingFrontText(player);
        sign.updateText(current -> current.setMessage(line - 1, Component.literal(text)), front);
        sign.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        player.sendSystemMessage(Messages.success("Sign line " + line + " set."));
        return 1;
    }

    private static int clearSign(CommandContext<CommandSourceStack> context, Integer line) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = lookedBlock(player).getBlockPos();
        SignBlockEntity sign = signAt(level, pos, player);
        if (sign == null) {
            return 0;
        }
        boolean front = sign.isFacingFrontText(player);
        sign.updateText(current -> {
            SignText updated = current;
            if (line == null) {
                for (int index = 0; index < SignText.LINES; index++) {
                    updated = updated.setMessage(index, Component.empty());
                }
            } else {
                updated = updated.setMessage(line - 1, Component.empty());
            }
            return updated;
        }, front);
        sign.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        player.sendSystemMessage(Messages.success(line == null ? "Sign cleared." : "Sign line " + line + " cleared."));
        return 1;
    }

    private static int vanish(CommandContext<CommandSourceStack> context, ServerPlayer target, Boolean requestedState) throws CommandSyntaxException {
        if (target == null) {
            return 0;
        }
        boolean vanished = requestedState == null ? !target.isInvisible() : requestedState;
        target.setInvisible(vanished);
        context.getSource().sendSystemMessage(Messages.success(target.getDisplayName().getString() + " vanish " + (vanished ? "enabled." : "disabled.")));
        if (context.getSource().getPlayer() != target) {
            target.sendSystemMessage(Messages.success("Vanish " + (vanished ? "enabled." : "disabled.") + "."));
        }
        return 1;
    }

    private static int clearFirework(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = fireworkStack(player);
        if (stack.isEmpty()) {
            player.sendSystemMessage(Messages.error("Hold a firework rocket first."));
            return 0;
        }
        stack.remove(DataComponents.FIREWORKS);
        player.sendSystemMessage(Messages.success("Firework effects cleared."));
        return 1;
    }

    private static int setFireworkPower(CommandContext<CommandSourceStack> context, int power) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = fireworkStack(player);
        if (stack.isEmpty()) {
            player.sendSystemMessage(Messages.error("Hold a firework rocket first."));
            return 0;
        }
        Fireworks current = stack.getOrDefault(DataComponents.FIREWORKS, new Fireworks(1, List.of()));
        stack.set(DataComponents.FIREWORKS, new Fireworks(power, current.explosions()));
        player.sendSystemMessage(Messages.success("Firework power set to " + power + "."));
        return 1;
    }

    private static int setFirework(CommandContext<CommandSourceStack> context, String rawMeta) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = fireworkStack(player);
        if (stack.isEmpty()) {
            player.sendSystemMessage(Messages.error("Hold a firework rocket first."));
            return 0;
        }
        Fireworks fireworks = parseFireworks(rawMeta, context.getSource());
        if (fireworks == null) {
            return 0;
        }
        stack.set(DataComponents.FIREWORKS, fireworks);
        player.sendSystemMessage(Messages.success("Firework updated."));
        return 1;
    }

    private static int launchFireworks(CommandContext<CommandSourceStack> context, int amount) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = fireworkStack(player);
        if (stack.isEmpty()) {
            player.sendSystemMessage(Messages.error("Hold a firework rocket first."));
            return 0;
        }
        ServerLevel level = (ServerLevel) player.level();
        Vec3 direction = player.getLookAngle().normalize();
        Vec3 position = player.getEyePosition().add(direction.scale(1.0D));
        for (int index = 0; index < amount; index++) {
            FireworkRocketEntity firework = new FireworkRocketEntity(level, stack.copyWithCount(1), position.x, position.y, position.z, true);
            firework.setDeltaMovement(direction.scale(0.5D).add(0.0D, 0.2D + index * 0.01D, 0.0D));
            level.addFreshEntity(firework);
        }
        player.sendSystemMessage(Messages.success("Launched " + amount + " firework(s)."));
        return amount;
    }

    private static ResourceKey<ConfiguredFeature<?, ?>> treeFeature(String rawType, boolean large) {
        return switch (rawType.toLowerCase(Locale.ROOT).replace("-", "_")) {
            case "birch" -> large ? TreeFeatures.SUPER_BIRCH_BEES : TreeFeatures.BIRCH;
            case "redwood", "spruce" -> large ? TreeFeatures.MEGA_SPRUCE : TreeFeatures.SPRUCE;
            case "pine" -> large ? TreeFeatures.MEGA_PINE : TreeFeatures.PINE;
            case "jungle" -> large ? TreeFeatures.MEGA_JUNGLE_TREE : TreeFeatures.JUNGLE_TREE;
            case "darkoak", "dark_oak" -> TreeFeatures.DARK_OAK;
            case "acacia" -> TreeFeatures.ACACIA;
            case "cherry" -> TreeFeatures.CHERRY;
            case "mangrove" -> large ? TreeFeatures.TALL_MANGROVE : TreeFeatures.MANGROVE;
            case "swamp", "swampoak", "swamp_oak" -> TreeFeatures.SWAMP_OAK;
            case "brownmushroom", "brown_mushroom" -> TreeFeatures.HUGE_BROWN_MUSHROOM;
            case "redmushroom", "red_mushroom" -> TreeFeatures.HUGE_RED_MUSHROOM;
            default -> large ? TreeFeatures.FANCY_OAK : TreeFeatures.OAK;
        };
    }

    private static String optionalString(CommandContext<CommandSourceStack> context, String argument, String fallback) {
        try {
            return getString(context, argument);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static ItemStack fireworkStack(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() == Items.FIREWORK_ROCKET) {
            return main;
        }
        ItemStack offhand = player.getOffhandItem();
        if (offhand.getItem() == Items.FIREWORK_ROCKET) {
            return offhand;
        }
        return ItemStack.EMPTY;
    }

    private static Fireworks parseFireworks(String rawMeta, CommandSourceStack source) {
        int power = 1;
        FireworkExplosion.Shape shape = FireworkExplosion.Shape.SMALL_BALL;
        IntList colors = new IntArrayList();
        IntList fadeColors = new IntArrayList();
        colors.add(DyeColor.RED.getFireworkColor());
        boolean trail = false;
        boolean twinkle = false;

        for (String token : rawMeta.trim().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            int separator = token.indexOf(':');
            if (separator < 0) {
                separator = token.indexOf('=');
            }
            String key = separator < 0 ? token : token.substring(0, separator);
            String value = separator < 0 ? token : token.substring(separator + 1);
            String normalizedKey = key.toLowerCase(Locale.ROOT);

            try {
                switch (normalizedKey) {
                    case "power", "p" -> power = clampFireworkPower(Integer.parseInt(value));
                    case "color", "colors", "c" -> colors = parseFireworkColors(value);
                    case "fade", "fadecolor", "fadecolors", "f" -> fadeColors = parseFireworkColors(value);
                    case "shape", "type", "s" -> shape = parseFireworkShape(value);
                    case "effect", "effects", "e" -> {
                        for (String effect : value.split(",")) {
                            if ("trail".equalsIgnoreCase(effect)) {
                                trail = true;
                            } else if ("twinkle".equalsIgnoreCase(effect) || "flicker".equalsIgnoreCase(effect)) {
                                twinkle = true;
                            } else if (!effect.isBlank()) {
                                source.sendSystemMessage(Messages.error("Unknown firework effect: " + effect));
                                return null;
                            }
                        }
                    }
                    case "trail" -> trail = true;
                    case "twinkle", "flicker" -> twinkle = true;
                    default -> {
                        DyeColor dye = DyeColor.byName(value.toLowerCase(Locale.ROOT), null);
                        if (dye == null) {
                            source.sendSystemMessage(Messages.error("Unknown firework option: " + token));
                            return null;
                        }
                        colors = new IntArrayList();
                        colors.add(dye.getFireworkColor());
                    }
                }
            } catch (NumberFormatException exception) {
                source.sendSystemMessage(Messages.error("Invalid firework power: " + value));
                return null;
            } catch (IllegalArgumentException exception) {
                source.sendSystemMessage(Messages.error(exception.getMessage()));
                return null;
            }
        }

        if (colors.isEmpty()) {
            colors.add(DyeColor.RED.getFireworkColor());
        }
        FireworkExplosion explosion = new FireworkExplosion(shape, colors, fadeColors, trail, twinkle);
        return new Fireworks(power, List.of(explosion));
    }

    private static int clampFireworkPower(int power) {
        return Math.max(0, Math.min(4, power));
    }

    private static IntList parseFireworkColors(String rawColors) {
        IntList colors = new IntArrayList();
        for (String rawColor : rawColors.split(",")) {
            String color = rawColor.trim().toLowerCase(Locale.ROOT);
            if (color.isBlank()) {
                continue;
            }
            if (color.startsWith("#") && color.length() == 7) {
                colors.add(Integer.parseUnsignedInt(color.substring(1), 16));
                continue;
            }
            DyeColor dye = DyeColor.byName(color, null);
            if (dye == null) {
                throw new IllegalArgumentException("Unknown firework color: " + rawColor);
            }
            colors.add(dye.getFireworkColor());
        }
        return colors;
    }

    private static FireworkExplosion.Shape parseFireworkShape(String rawShape) {
        return switch (rawShape.toLowerCase(Locale.ROOT).replace("-", "_")) {
            case "ball", "small", "small_ball" -> FireworkExplosion.Shape.SMALL_BALL;
            case "large", "large_ball", "big", "big_ball" -> FireworkExplosion.Shape.LARGE_BALL;
            case "star" -> FireworkExplosion.Shape.STAR;
            case "creeper", "creeper_face" -> FireworkExplosion.Shape.CREEPER;
            case "burst" -> FireworkExplosion.Shape.BURST;
            default -> throw new IllegalArgumentException("Unknown firework shape: " + rawShape);
        };
    }

    private static int remove(CommandContext<CommandSourceStack> context, String rawType, int radius) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        String type = rawType.toLowerCase(Locale.ROOT);
        AABB area = new AABB(
            player.getX() - radius,
            player.getY() - radius,
            player.getZ() - radius,
            player.getX() + radius,
            player.getY() + radius,
            player.getZ() + radius
        );
        List<Entity> entities = level.getEntities(player, area, entity -> shouldRemove(entity, type));
        entities.forEach(Entity::discard);
        context.getSource().sendSystemMessage(Messages.success("Removed " + entities.size() + " entit(y/ies)."));
        return entities.size();
    }

    private static boolean shouldRemove(Entity entity, String type) {
        if (entity instanceof Player) {
            return false;
        }
        return switch (type) {
            case "all" -> true;
            case "drops", "items" -> entity.getType() == EntityType.ITEM;
            case "mobs" -> entity instanceof LivingEntity;
            case "monsters" -> entity instanceof Enemy;
            case "animals" -> entity instanceof Animal;
            default -> EntityType.getKey(entity.getType()).getPath().equals(type) || EntityType.getKey(entity.getType()).toString().equals(type);
        };
    }

    private static void spawnLightning(ServerLevel level, Vec3 position, ServerPlayer cause) {
        LightningBolt lightning = new LightningBolt(EntityType.LIGHTNING_BOLT, level);
        lightning.setPos(position);
        lightning.setCause(cause);
        level.addFreshEntity(lightning);
    }

    private static Vec3 lookedPosition(ServerPlayer player) throws CommandSyntaxException {
        HitResult hit = player.pick(200.0D, 0.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
            return Vec3.atCenterOf(blockHit.getBlockPos());
        }
        return player.getEyePosition().add(player.getLookAngle().normalize().scale(32.0D));
    }

    private static BlockHitResult lookedBlock(ServerPlayer player) throws CommandSyntaxException {
        HitResult hit = player.pick(200.0D, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
            player.sendSystemMessage(Messages.error("No block in sight."));
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
        }
        return blockHit;
    }

    private static ServerPlayer resolvePlayer(CommandContext<CommandSourceStack> context) {
        String rawTarget = getString(context, TARGET_ARGUMENT);
        return context.getSource().getServer().getPlayerList().getPlayers().stream()
            .filter(player -> player.getScoreboardName().equalsIgnoreCase(rawTarget) || player.getDisplayName().getString().equalsIgnoreCase(rawTarget))
            .findFirst()
            .orElseGet(() -> {
                context.getSource().sendSystemMessage(Messages.error("Player not found: " + rawTarget));
                return null;
            });
    }

    private static SignBlockEntity signAt(ServerLevel level, BlockPos pos, ServerPlayer player) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof SignBlockEntity sign) {
            return sign;
        }
        player.sendSystemMessage(Messages.error("No sign in sight."));
        return null;
    }

    private static boolean parseState(String rawState) {
        return switch (rawState.toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable", "enabled", "yes" -> true;
            case "off", "false", "disable", "disabled", "no" -> false;
            default -> throw new IllegalArgumentException("Expected on/off.");
        };
    }

    private static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> suggestPlayers() {
        return WorldUtilityCommands::suggestPlayers;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestFireworkMeta(
        CommandContext<CommandSourceStack> context,
        com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        return SharedSuggestionProvider.suggest(
            List.of(
                "color:red shape:ball power:1",
                "color:blue,white fade:red shape:star effect:trail,twinkle power:2",
                "color:green shape:creeper effect:twinkle",
                "color:#ff8800 fade:#ffff00 shape:burst power:3"
            ),
            builder
        );
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPlayers(
        CommandContext<CommandSourceStack> context,
        com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        return SharedSuggestionProvider.suggest(
            context.getSource().getServer().getPlayerList().getPlayers().stream()
                .map(ServerPlayer::getScoreboardName)
                .toList(),
            builder
        );
    }

    private interface CommandFactory {
        LiteralArgumentBuilder<CommandSourceStack> create(String name);
    }
}
