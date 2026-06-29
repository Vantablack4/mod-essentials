package com.vantablack4.essentials;

import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static com.mojang.brigadier.arguments.DoubleArgumentType.getDouble;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

import java.lang.management.ManagementFactory;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import com.vantablack4.essentials.commands.admin.AdminCommands;
import com.vantablack4.essentials.commands.economy.EconomyCommands;
import com.vantablack4.essentials.commands.info.InfoCommands;
import com.vantablack4.essentials.commands.gui.GuiUtilityCommands;
import com.vantablack4.essentials.commands.integration.IntegrationCommands;
import com.vantablack4.essentials.commands.item.EssentialsItemCommands;
import com.vantablack4.essentials.commands.jail.JailCommands;
import com.vantablack4.essentials.commands.kit.KitCommands;
import com.vantablack4.essentials.commands.player.PlayerUtilityCommands;
import com.vantablack4.essentials.commands.social.SocialCommands;
import com.vantablack4.essentials.commands.world.WorldUtilityCommands;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.GameModeArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class EssentialsCommands {
    private static final String NAME_ARGUMENT = "name";
    private static final String TARGET_ARGUMENT = "target";
    private static final String DESTINATION_ARGUMENT = "destination";
    private static final String POSITION_ARGUMENT = "position";
    private static final String DIMENSION_ARGUMENT = "dimension";
    private static final String MESSAGE_ARGUMENT = "message";
    private static final String ENABLED_ARGUMENT = "enabled";
    private static final String TARGET_OR_STATE_ARGUMENT = "targetOrState";
    private static final String STATE_ARGUMENT = "state";
    private static final String GAMEMODE_ARGUMENT = "gamemode";
    private static final String SPEED_ARGUMENT = "speed";
    private static final String RADIUS_ARGUMENT = "radius";
    private static final String SECONDS_ARGUMENT = "seconds";
    private static final String MODE_ARGUMENT = "mode";
    private static final String TIME_ARGUMENT = "time";
    private static final int DEFAULT_NEAR_RADIUS = 100;
    private static final int DEFAULT_WEATHER_SECONDS = 300;

    private final EssentialsConfig config;
    private final EssentialsStorage storage;
    private final BackService backService;
    private final TpaService tpaService;
    private final PlayerStateService playerStateService;
    private final PermissionChecks permissions;
    private final AdminCommands adminCommands;
    private final JailCommands jailCommands;
    private final InfoCommands infoCommands;
    private final KitCommands kitCommands;
    private final PlayerUtilityCommands playerUtilityCommands;
    private final SocialCommands socialCommands;

    public EssentialsCommands(
        EssentialsConfig config,
        EssentialsStorage storage,
        BackService backService,
        TpaService tpaService,
        PlayerStateService playerStateService
    ) {
        this.config = config;
        this.storage = storage;
        this.backService = backService;
        this.tpaService = tpaService;
        this.playerStateService = playerStateService;
        this.permissions = new PermissionChecks(config);
        this.adminCommands = new AdminCommands(config, permissions);
        this.jailCommands = new JailCommands(config, permissions);
        this.infoCommands = new InfoCommands(config, permissions);
        this.kitCommands = new KitCommands(config, permissions::admin);
        this.playerUtilityCommands = new PlayerUtilityCommands(config, permissions::admin);
        this.socialCommands = new SocialCommands(config.configDirectory(), permissions);
    }

    public void register() {
        adminCommands.registerLifecycle();
        jailCommands.registerLifecycle();
        infoCommands.registerLifecycle();
        playerUtilityCommands.registerLifecycle();
        socialCommands.registerLifecycle();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher, registryAccess));
        ServerTickEvents.END_SERVER_TICK.register(server -> tpaService.pruneExpired());
    }

    private void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(root("vessentials"));
        dispatcher.register(root("essentials"));

        dispatcher.register(spawnCommand("spawn"));
        dispatcher.register(setSpawnCommand("setspawn"));

        dispatcher.register(Commands.literal("home")
            .executes(context -> home(context, "home"))
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestHomes)
                .executes(context -> home(context, getString(context, NAME_ARGUMENT)))));
        dispatcher.register(Commands.literal("sethome")
            .executes(context -> setHome(context, "home"))
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes(context -> setHome(context, getString(context, NAME_ARGUMENT)))));
        dispatcher.register(Commands.literal("delhome")
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestHomes)
                .executes(context -> deleteHome(context, getString(context, NAME_ARGUMENT)))));
        dispatcher.register(Commands.literal("homes").executes(this::homes));

        dispatcher.register(Commands.literal("warp")
            .executes(this::warps)
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestWarps)
                .executes(context -> warp(context, getString(context, NAME_ARGUMENT)))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .requires(permissions::admin)
                    .suggests(this::suggestPlayers)
                    .executes(context -> {
                        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                        return target == null ? 0 : warp(context, getString(context, NAME_ARGUMENT), target);
                    }))));
        dispatcher.register(Commands.literal("warps").executes(this::warps));
        dispatcher.register(Commands.literal("setwarp").requires(permissions::admin)
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes(context -> setWarp(context, getString(context, NAME_ARGUMENT)))));
        dispatcher.register(Commands.literal("delwarp").requires(permissions::admin)
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestWarps)
                .executes(context -> deleteWarp(context, getString(context, NAME_ARGUMENT)))));

        dispatcher.register(backCommand("back"));
        dispatcher.register(tpaCommand("tpa", TpaService.RequestType.TO));
        dispatcher.register(tpaCommand("tpask", TpaService.RequestType.TO));
        dispatcher.register(tpaCommand("tpahere", TpaService.RequestType.HERE));
        dispatcher.register(tpaallCommand("tpaall"));
        dispatcher.register(tpacceptCommand("tpaccept"));
        dispatcher.register(tpacceptCommand("tpyes"));
        dispatcher.register(tpdenyCommand("tpdeny"));
        dispatcher.register(tpdenyCommand("tpno"));
        dispatcher.register(tpacancelCommand("tpacancel"));
        dispatcher.register(tptoggleCommand("tptoggle"));
        dispatcher.register(tpautoCommand("tpauto"));

        dispatcher.register(teleportCommand("tp"));
        dispatcher.register(teleportCommand("teleport"));
        dispatcher.register(teleportCommand("tpo", true));
        dispatcher.register(tphereCommand("tphere"));
        dispatcher.register(tphereCommand("s"));
        dispatcher.register(tphereCommand("tpohere", true));
        dispatcher.register(tpallCommand("tpall"));
        dispatcher.register(tpposCommand("tppos"));
        dispatcher.register(topCommand("top"));

        dispatcher.register(healCommand("heal"));
        dispatcher.register(feedCommand("feed"));
        dispatcher.register(flyCommand("fly"));
        dispatcher.register(godCommand("god"));
        dispatcher.register(gamemodeCommand("gamemode"));
        dispatcher.register(gamemodeCommand("gm"));
        dispatcher.register(gamemodeShortcut("gms", GameType.SURVIVAL));
        dispatcher.register(gamemodeShortcut("gmc", GameType.CREATIVE));
        dispatcher.register(gamemodeShortcut("gma", GameType.ADVENTURE));
        dispatcher.register(gamemodeShortcut("gmsp", GameType.SPECTATOR));
        dispatcher.register(speedCommand("speed"));

        dispatcher.register(clearInventoryCommand("clearinventory"));
        dispatcher.register(clearInventoryCommand("ci"));
        dispatcher.register(clearInventoryConfirmToggleCommand("clearinventoryconfirmtoggle"));
        dispatcher.register(repairCommand("repair"));
        dispatcher.register(hatCommand("hat"));
        dispatcher.register(enderChestCommand("enderchest"));
        dispatcher.register(enderChestCommand("ec"));
        dispatcher.register(extinguishCommand("ext"));
        dispatcher.register(extinguishCommand("extinguish"));
        dispatcher.register(nearCommand("near"));
        dispatcher.register(pingCommand("ping"));
        dispatcher.register(getPosCommand("getpos"));
        dispatcher.register(depthCommand("depth"));
        dispatcher.register(compassCommand("compass"));
        dispatcher.register(suicideCommand("suicide"));
        dispatcher.register(killCommand("kill"));
        dispatcher.register(sudoCommand("sudo"));
        dispatcher.register(broadcastWorldCommand("broadcastworld"));
        dispatcher.register(burnCommand("burn"));
        dispatcher.register(restCommand("rest"));
        dispatcher.register(listCommand("list"));
        dispatcher.register(gcCommand("gc"));
        dispatcher.register(renameHomeCommand("renamehome"));
        dispatcher.register(warpInfoCommand("warpinfo"));
        dispatcher.register(worldCommand("world"));
        dispatcher.register(jumpCommand("jump"));
        dispatcher.register(bottomCommand("bottom"));
        GuiUtilityCommands.register(dispatcher);

        dispatcher.register(timeCommand("time"));
        dispatcher.register(timeAliasCommand("day", "day"));
        dispatcher.register(timeAliasCommand("night", "night"));
        dispatcher.register(timeAliasCommand("noon", "noon"));
        dispatcher.register(timeAliasCommand("midnight", "midnight"));
        dispatcher.register(weatherCommand("sun", false, false, "Hava açıldı."));
        dispatcher.register(weatherCommand("storm", true, false, "Yağmur başladı."));
        dispatcher.register(weatherRootCommand("weather"));
        dispatcher.register(thunderCommand("thunder"));

        dispatcher.register(broadcastCommand("broadcast"));
        dispatcher.register(broadcastCommand("bc"));
        dispatcher.register(broadcastCommand("alert"));

        registerUpstreamAliases(dispatcher);
        adminCommands.register(dispatcher);
        jailCommands.register(dispatcher);
        infoCommands.register(dispatcher);
        EconomyCommands.register(dispatcher, registryAccess, config.configDirectory(), permissions::admin);
        EssentialsItemCommands.register(dispatcher, registryAccess, permissions::admin);
        kitCommands.register(dispatcher);
        playerUtilityCommands.register(dispatcher, registryAccess);
        WorldUtilityCommands.register(dispatcher, registryAccess, permissions::admin);
        IntegrationCommands.register(dispatcher, permissions::admin);
        socialCommands.register(dispatcher);
    }

    private void registerUpstreamAliases(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerAll(dispatcher, this::root, "eessentials", "ess", "eess", "essversion");
        registerAll(dispatcher, this::spawnCommand, "espawn");
        registerAll(dispatcher, this::setSpawnCommand, "esetspawn");
        registerAll(dispatcher, this::backCommand, "eback", "return", "ereturn");

        registerAll(dispatcher, this::broadcastCommand, "ebc", "bcast", "ebcast", "ebroadcast", "shout", "eshout");
        registerAll(dispatcher, this::clearInventoryCommand, "eci", "clean", "eclean", "clear", "eclear", "clearinvent", "eclearinvent", "eclearinventory");
        registerAll(dispatcher, this::clearInventoryConfirmToggleCommand, "eclearinventoryconfirmtoggle", "clearinventoryconfirmoff", "eclearinventoryconfirmoff", "clearconfirmoff", "eclearconfirmoff", "clearconfirmon", "eclearconfirmon", "clearconfirm", "eclearconfirm");
        registerAll(dispatcher, this::feedCommand, "eat", "eeat", "efeed");
        registerAll(dispatcher, this::flyCommand, "efly");
        registerAll(dispatcher, this::godCommand, "egod", "godmode", "egodmode", "tgm", "etgm");
        registerAll(dispatcher, this::healCommand, "eheal");
        registerAll(dispatcher, this::hatCommand, "ehat", "head", "ehead");
        registerAll(dispatcher, this::enderChestCommand, "echest", "eechest", "eenderchest", "endersee", "eendersee", "eec");
        registerAll(dispatcher, this::extinguishCommand, "eext", "eextinguish");
        registerAll(dispatcher, this::nearCommand, "enear", "nearby", "enearby");
        registerAll(dispatcher, this::pingCommand, "echo", "eecho", "eping", "pong", "epong");
        registerAll(dispatcher, this::getPosCommand, "coords", "egetpos", "position", "eposition", "whereami", "ewhereami", "getlocation", "egetlocation", "getloc", "egetloc");
        registerAll(dispatcher, this::depthCommand, "edepth", "height", "eheight");
        registerAll(dispatcher, this::compassCommand, "ecompass", "direction", "edirection");
        registerAll(dispatcher, this::suicideCommand, "esuicide");
        registerAll(dispatcher, this::killCommand, "ekill");
        registerAll(dispatcher, this::sudoCommand, "esudo");
        registerAll(dispatcher, this::broadcastWorldCommand, "bcw", "ebcw", "bcastw", "ebcastw", "ebroadcastworld", "shoutworld", "eshoutworld");
        registerAll(dispatcher, this::burnCommand, "eburn");
        registerAll(dispatcher, this::restCommand, "erest");
        registerAll(dispatcher, this::listCommand, "elist", "online", "eonline", "playerlist", "eplayerlist", "plist", "eplist", "who", "ewho");
        registerAll(dispatcher, this::gcCommand, "lag", "elag", "egc", "mem", "emem", "memory", "ememory", "uptime", "euptime", "tps", "etps", "entities", "eentities");
        registerAll(dispatcher, this::renameHomeCommand, "erenamehome");
        registerAll(dispatcher, this::warpInfoCommand, "ewarpinfo");
        registerAll(dispatcher, this::worldCommand, "eworld");
        registerAll(dispatcher, this::jumpCommand, "j", "ej", "ejump", "jumpto", "ejumpto");
        registerAll(dispatcher, this::bottomCommand, "ebottom");
        registerAll(dispatcher, this::repairCommand, "fix", "efix", "erepair");

        registerAll(dispatcher, this::gamemodeCommand, "egamemode", "egm");
        registerAll(dispatcher, name -> gamemodeShortcut(name, GameType.SURVIVAL), "survival", "esurvival", "survivalmode", "esurvivalmode", "egms");
        registerAll(dispatcher, name -> gamemodeShortcut(name, GameType.CREATIVE), "creative", "ecreative", "eecreative", "creativemode", "ecreativemode", "egmc");
        registerAll(dispatcher, name -> gamemodeShortcut(name, GameType.ADVENTURE), "adventure", "eadventure", "adventuremode", "eadventuremode", "egma");
        registerAll(dispatcher, name -> gamemodeShortcut(name, GameType.SPECTATOR), "gmt", "egmt", "sp", "egmsp", "spec", "spectator");

        registerAll(dispatcher, this::teleportCommand, "tele", "etele", "eteleport", "etp", "tp2p", "etp2p");
        registerAll(dispatcher, this::tphereCommand, "etphere");
        registerAll(dispatcher, name -> teleportCommand(name, true), "etpo");
        registerAll(dispatcher, name -> tphereCommand(name, true), "etpohere");
        registerAll(dispatcher, this::tpallCommand, "etpall");
        registerAll(dispatcher, this::tpposCommand, "etppos");
        registerAll(dispatcher, this::topCommand, "etop");
        registerAll(dispatcher, name -> tpaCommand(name, TpaService.RequestType.TO), "call", "ecall", "etpa", "etpask");
        registerAll(dispatcher, name -> tpaCommand(name, TpaService.RequestType.HERE), "etpahere");
        registerAll(dispatcher, this::tpaallCommand, "etpaall");
        registerAll(dispatcher, this::tpacceptCommand, "etpaccept", "etpyes");
        registerAll(dispatcher, this::tpdenyCommand, "etpdeny", "etpno");
        registerAll(dispatcher, this::tpacancelCommand, "etpacancel");
        registerAll(dispatcher, this::tptoggleCommand, "etptoggle");
        registerAll(dispatcher, this::tpautoCommand, "etpauto");

        registerAll(dispatcher, this::homeCommand, "ehome", "ehomes");
        registerAll(dispatcher, this::setHomeCommand, "esethome", "createhome", "ecreatehome");
        registerAll(dispatcher, this::deleteHomeCommand, "edelhome", "remhome", "eremhome", "rmhome", "ermhome");
        registerAll(dispatcher, this::warpCommand, "ewarp", "ewarps");
        registerAll(dispatcher, this::setWarpCommand, "createwarp", "ecreatewarp", "esetwarp");
        registerAll(dispatcher, this::deleteWarpCommand, "edelwarp", "remwarp", "eremwarp", "rmwarp", "ermwarp");

        registerAll(dispatcher, name -> speedCommand(name, null), "espeed");
        registerAll(dispatcher, name -> speedCommand(name, SpeedMode.FLY), "flyspeed", "eflyspeed", "fspeed", "efspeed");
        registerAll(dispatcher, name -> speedCommand(name, SpeedMode.WALK), "walkspeed", "ewalkspeed", "wspeed", "ewspeed");
        registerAll(dispatcher, name -> timeCommand(name), "etime");
        registerAll(dispatcher, name -> timeAliasCommand(name, "day"), "eday");
        registerAll(dispatcher, name -> timeAliasCommand(name, "night"), "enight");
        registerAll(dispatcher, name -> weatherCommand(name, true, false, "Yağmur başladı."), "rain", "erain", "estorm");
        registerAll(dispatcher, name -> weatherCommand(name, false, false, "Hava açıldı."), "sky", "esky", "esun");
        registerAll(dispatcher, name -> weatherRootCommand(name), "eweather");
        registerAll(dispatcher, name -> thunderCommand(name), "ethunder");
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

    private LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return Commands.literal(name)
            .executes(this::status)
            .then(Commands.literal("help").executes(this::help))
            .then(Commands.literal("status").executes(this::status));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tpaCommand(String name, TpaService.RequestType type) {
        return Commands.literal(name)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> tpa(context, type)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> spawnCommand(String name) {
        return Commands.literal(name)
            .executes(this::spawn)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .requires(permissions::admin)
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : spawn(context, target);
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> setSpawnCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> setSpawn(context, "default"))
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes(context -> setSpawn(context, getString(context, NAME_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tpaallCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> tpaall(context, context.getSource().getPlayerOrException()))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : tpaall(context, target);
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tpacceptCommand(String name) {
        return Commands.literal(name)
            .executes(context -> tpaccept(context, null))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPendingRequesters)
                .executes(context -> tpaccept(context, getString(context, TARGET_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tpdenyCommand(String name) {
        return Commands.literal(name)
            .executes(context -> tpdeny(context, null))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPendingRequesters)
                .executes(context -> tpdeny(context, getString(context, TARGET_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tpacancelCommand(String name) {
        return Commands.literal(name)
            .executes(context -> tpacancel(context, null))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestOutgoingTargets)
                .executes(context -> tpacancel(context, getString(context, TARGET_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tptoggleCommand(String name) {
        return teleportStateCommand(name, false);
    }

    private LiteralArgumentBuilder<CommandSourceStack> tpautoCommand(String name) {
        return teleportStateCommand(name, true);
    }

    private LiteralArgumentBuilder<CommandSourceStack> teleportStateCommand(String name, boolean auto) {
        return Commands.literal(name)
            .executes(context -> teleportState(context, auto, null, null))
            .then(Commands.argument(TARGET_OR_STATE_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayersAndToggleStates)
                .executes(context -> teleportState(context, auto, getString(context, TARGET_OR_STATE_ARGUMENT), null))
                .then(Commands.argument(STATE_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestToggleStates)
                    .executes(context -> teleportState(
                        context,
                        auto,
                        getString(context, TARGET_OR_STATE_ARGUMENT),
                        getString(context, STATE_ARGUMENT)
                    ))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> backCommand(String name) {
        return Commands.literal(name)
            .executes(this::back)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .requires(permissions::admin)
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : back(context, target);
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> homeCommand(String name) {
        return Commands.literal(name)
            .executes(context -> home(context, "home"))
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestHomes)
                .executes(context -> home(context, getString(context, NAME_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> setHomeCommand(String name) {
        return Commands.literal(name)
            .executes(context -> setHome(context, "home"))
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes(context -> setHome(context, getString(context, NAME_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> deleteHomeCommand(String name) {
        return Commands.literal(name)
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestHomes)
                .executes(context -> deleteHome(context, getString(context, NAME_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> warpCommand(String name) {
        return Commands.literal(name)
            .executes(this::warps)
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestWarps)
                .executes(context -> warp(context, getString(context, NAME_ARGUMENT)))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .requires(permissions::admin)
                    .suggests(this::suggestPlayers)
                    .executes(context -> {
                        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                        return target == null ? 0 : warp(context, getString(context, NAME_ARGUMENT), target);
                    })));
    }

    private LiteralArgumentBuilder<CommandSourceStack> setWarpCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes(context -> setWarp(context, getString(context, NAME_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> deleteWarpCommand(String name) {
        return Commands.literal(name).requires(permissions::admin)
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestWarps)
                .executes(context -> deleteWarp(context, getString(context, NAME_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> teleportCommand(String name) {
        return teleportCommand(name, false);
    }

    private LiteralArgumentBuilder<CommandSourceStack> teleportCommand(String name, boolean bypassToggle) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> teleportSelfToTarget(context, bypassToggle))
                .then(Commands.argument(DESTINATION_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestPlayers)
                    .executes(context -> teleportTargetToTarget(context, bypassToggle)))
                .then(Commands.argument(POSITION_ARGUMENT, Vec3Argument.vec3())
                    .executes(context -> teleportTargetToPosition(context, context.getSource().getLevel(), bypassToggle))
                    .then(Commands.argument(DIMENSION_ARGUMENT, DimensionArgument.dimension())
                        .executes(context -> teleportTargetToPosition(context, DimensionArgument.getDimension(context, DIMENSION_ARGUMENT), bypassToggle)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tphereCommand(String name) {
        return tphereCommand(name, false);
    }

    private LiteralArgumentBuilder<CommandSourceStack> tphereCommand(String name, boolean bypassToggle) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> teleportTargetHere(context, bypassToggle)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tpallCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> tpall(context, context.getSource().getPlayerOrException()))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : tpall(context, target);
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> tpposCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(POSITION_ARGUMENT, Vec3Argument.vec3())
                .executes(context -> teleportSelfToPosition(context, context.getSource().getLevel()))
                .then(Commands.argument(DIMENSION_ARGUMENT, DimensionArgument.dimension())
                    .executes(context -> teleportSelfToPosition(context, DimensionArgument.getDimension(context, DIMENSION_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> topCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(this::top);
    }

    private LiteralArgumentBuilder<CommandSourceStack> healCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> heal(context, context.getSource().getPlayerOrException()))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : heal(context, target);
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> feedCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> feed(context, context.getSource().getPlayerOrException()))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : feed(context, target);
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> flyCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> fly(context, context.getSource().getPlayerOrException(), null))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : fly(context, target, null);
                })
                .then(Commands.argument(ENABLED_ARGUMENT, BoolArgumentType.bool())
                    .executes(context -> {
                        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                        return target == null ? 0 : fly(context, target, getBool(context, ENABLED_ARGUMENT));
                    })));
    }

    private LiteralArgumentBuilder<CommandSourceStack> godCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> god(context, context.getSource().getPlayerOrException(), null))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : god(context, target, null);
                })
                .then(Commands.argument(ENABLED_ARGUMENT, BoolArgumentType.bool())
                    .executes(context -> {
                        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                        return target == null ? 0 : god(context, target, getBool(context, ENABLED_ARGUMENT));
                    })));
    }

    private LiteralArgumentBuilder<CommandSourceStack> gamemodeCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(GAMEMODE_ARGUMENT, GameModeArgument.gameMode())
                .executes(context -> gamemode(context, context.getSource().getPlayerOrException(), GameModeArgument.getGameMode(context, GAMEMODE_ARGUMENT)))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestPlayers)
                    .executes(context -> {
                        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                        return target == null ? 0 : gamemode(context, target, GameModeArgument.getGameMode(context, GAMEMODE_ARGUMENT));
                    })));
    }

    private LiteralArgumentBuilder<CommandSourceStack> gamemodeShortcut(String name, GameType gameType) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> gamemode(context, context.getSource().getPlayerOrException(), gameType))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : gamemode(context, target, gameType);
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> speedCommand(String name) {
        return speedCommand(name, null);
    }

    private LiteralArgumentBuilder<CommandSourceStack> speedCommand(String name, SpeedMode forcedMode) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(SPEED_ARGUMENT, DoubleArgumentType.doubleArg(0.0D, 10.0D))
                .executes(context -> speed(context, context.getSource().getPlayerOrException(), getDouble(context, SPEED_ARGUMENT), forcedMode))
                .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                    .suggests(this::suggestPlayers)
                    .executes(context -> {
                        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                        return target == null ? 0 : speed(context, target, getDouble(context, SPEED_ARGUMENT), forcedMode);
                    })))
            .then(Commands.literal("walk")
                .then(Commands.argument(SPEED_ARGUMENT, DoubleArgumentType.doubleArg(0.0D, 10.0D))
                    .executes(context -> speed(context, context.getSource().getPlayerOrException(), getDouble(context, SPEED_ARGUMENT), SpeedMode.WALK))
                    .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                        .suggests(this::suggestPlayers)
                        .executes(context -> {
                            ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                            return target == null ? 0 : speed(context, target, getDouble(context, SPEED_ARGUMENT), SpeedMode.WALK);
                        }))))
            .then(Commands.literal("fly")
                .then(Commands.argument(SPEED_ARGUMENT, DoubleArgumentType.doubleArg(0.0D, 10.0D))
                    .executes(context -> speed(context, context.getSource().getPlayerOrException(), getDouble(context, SPEED_ARGUMENT), SpeedMode.FLY))
                    .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                        .suggests(this::suggestPlayers)
                        .executes(context -> {
                            ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                            return target == null ? 0 : speed(context, target, getDouble(context, SPEED_ARGUMENT), SpeedMode.FLY);
                        }))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> clearInventoryCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> clearInventory(context, context.getSource().getPlayerOrException(), "/" + name))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : clearInventory(context, target, "/" + name + " " + getString(context, TARGET_ARGUMENT));
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> clearInventoryConfirmToggleCommand(String name) {
        return Commands.literal(name)
            .executes(context -> clearInventoryConfirmToggle(context, name));
    }

    private LiteralArgumentBuilder<CommandSourceStack> repairCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> repair(context, context.getSource().getPlayerOrException()))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : repair(context, target);
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> hatCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(this::hat)
            .then(Commands.literal("remove")
                .executes(this::removeHat));
    }

    private LiteralArgumentBuilder<CommandSourceStack> enderChestCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> enderChest(context, context.getSource().getPlayerOrException()))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : enderChest(context, target);
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> extinguishCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> extinguish(context, context.getSource().getPlayerOrException()))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : extinguish(context, target);
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> nearCommand(String name) {
        return Commands.literal(name)
            .executes(context -> near(context, DEFAULT_NEAR_RADIUS))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> nearTargetOrRadius(context, getString(context, TARGET_ARGUMENT), null))
                .then(Commands.argument(RADIUS_ARGUMENT, DoubleArgumentType.doubleArg(1.0D, 10000.0D))
                    .executes(context -> nearTargetOrRadius(
                        context,
                        getString(context, TARGET_ARGUMENT),
                        getDouble(context, RADIUS_ARGUMENT)
                    ))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> pingCommand(String name) {
        return Commands.literal(name)
            .executes(this::ping)
            .then(Commands.argument(MESSAGE_ARGUMENT, StringArgumentType.greedyString())
                .executes(this::pingEcho));
    }

    private LiteralArgumentBuilder<CommandSourceStack> getPosCommand(String name) {
        return Commands.literal(name)
            .executes(context -> getPos(context, context.getSource().getPlayerOrException(), null))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .requires(permissions::admin)
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : getPos(context, target, context.getSource().getPlayer());
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> depthCommand(String name) {
        return Commands.literal(name)
            .executes(this::depth);
    }

    private LiteralArgumentBuilder<CommandSourceStack> compassCommand(String name) {
        return Commands.literal(name)
            .executes(this::compass);
    }

    private LiteralArgumentBuilder<CommandSourceStack> suicideCommand(String name) {
        return Commands.literal(name)
            .executes(this::suicide);
    }

    private LiteralArgumentBuilder<CommandSourceStack> killCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : kill(context, target);
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> sudoCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .then(Commands.argument(MESSAGE_ARGUMENT, StringArgumentType.greedyString())
                    .executes(context -> sudo(context, getString(context, MESSAGE_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> broadcastWorldCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(DIMENSION_ARGUMENT, DimensionArgument.dimension())
                .then(Commands.argument(MESSAGE_ARGUMENT, StringArgumentType.greedyString())
                    .executes(context -> broadcastWorld(context, DimensionArgument.getDimension(context, DIMENSION_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> burnCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .suggests(this::suggestPlayers)
                .then(Commands.argument(SECONDS_ARGUMENT, IntegerArgumentType.integer(0))
                    .executes(context -> {
                        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                        return target == null ? 0 : burn(context, target, getInteger(context, SECONDS_ARGUMENT));
                    })));
    }

    private LiteralArgumentBuilder<CommandSourceStack> restCommand(String name) {
        return Commands.literal(name)
            .executes(context -> rest(context, context.getSource().getPlayerOrException()))
            .then(Commands.argument(TARGET_ARGUMENT, StringArgumentType.string())
                .requires(permissions::admin)
                .suggests(this::suggestPlayers)
                .executes(context -> {
                    ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
                    return target == null ? 0 : rest(context, target);
                }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> listCommand(String name) {
        return Commands.literal(name)
            .executes(this::listPlayers);
    }

    private LiteralArgumentBuilder<CommandSourceStack> gcCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(this::gc);
    }

    private LiteralArgumentBuilder<CommandSourceStack> renameHomeCommand(String name) {
        return Commands.literal(name)
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestHomes)
                .then(Commands.argument(DESTINATION_ARGUMENT, StringArgumentType.word())
                    .executes(context -> renameHome(context, getString(context, NAME_ARGUMENT), getString(context, DESTINATION_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> warpInfoCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestWarps)
                .executes(context -> warpInfo(context, getString(context, NAME_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> worldCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(this::worldToggle)
            .then(Commands.argument(DIMENSION_ARGUMENT, DimensionArgument.dimension())
                .executes(context -> world(context, DimensionArgument.getDimension(context, DIMENSION_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> jumpCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(this::jump);
    }

    private LiteralArgumentBuilder<CommandSourceStack> bottomCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(this::bottom);
    }

    private LiteralArgumentBuilder<CommandSourceStack> timeAliasCommand(String name, String value) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> setTime(context, context.getSource().getLevel(), value));
    }

    private LiteralArgumentBuilder<CommandSourceStack> timeCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> queryTime(context, context.getSource().getLevel()))
            .then(Commands.literal("set")
                .then(Commands.argument(TIME_ARGUMENT, StringArgumentType.word())
                    .suggests(this::suggestTimes)
                    .executes(context -> setTime(context, context.getSource().getLevel(), getString(context, TIME_ARGUMENT)))
                    .then(Commands.argument(DIMENSION_ARGUMENT, DimensionArgument.dimension())
                        .executes(context -> setTime(context, DimensionArgument.getDimension(context, DIMENSION_ARGUMENT), getString(context, TIME_ARGUMENT))))))
            .then(Commands.literal("add")
                .then(Commands.argument(TIME_ARGUMENT, IntegerArgumentType.integer())
                    .executes(context -> addTime(context, context.getSource().getLevel(), getInteger(context, TIME_ARGUMENT)))
                    .then(Commands.argument(DIMENSION_ARGUMENT, DimensionArgument.dimension())
                        .executes(context -> addTime(context, DimensionArgument.getDimension(context, DIMENSION_ARGUMENT), getInteger(context, TIME_ARGUMENT))))))
            .then(Commands.argument(TIME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestTimes)
                .executes(context -> setTime(context, context.getSource().getLevel(), getString(context, TIME_ARGUMENT)))
                .then(Commands.argument(DIMENSION_ARGUMENT, DimensionArgument.dimension())
                    .executes(context -> setTime(context, DimensionArgument.getDimension(context, DIMENSION_ARGUMENT), getString(context, TIME_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> weatherRootCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(MODE_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestWeatherModes)
                .executes(context -> weatherMode(context, getString(context, MODE_ARGUMENT), DEFAULT_WEATHER_SECONDS))
                .then(Commands.argument(SECONDS_ARGUMENT, IntegerArgumentType.integer(1))
                    .executes(context -> weatherMode(context, getString(context, MODE_ARGUMENT), getInteger(context, SECONDS_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> thunderCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(ENABLED_ARGUMENT, BoolArgumentType.bool())
                .executes(context -> thunder(context, getBool(context, ENABLED_ARGUMENT), DEFAULT_WEATHER_SECONDS))
                .then(Commands.argument(SECONDS_ARGUMENT, IntegerArgumentType.integer(1))
                    .executes(context -> thunder(context, getBool(context, ENABLED_ARGUMENT), getInteger(context, SECONDS_ARGUMENT)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> vanillaAlias(String name, String vanillaCommand, String successMessage) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> {
                context.getSource().getServer().getCommands().performPrefixedCommand(context.getSource(), vanillaCommand);
                context.getSource().sendSystemMessage(Messages.success(successMessage));
                return 1;
            });
    }

    private LiteralArgumentBuilder<CommandSourceStack> weatherCommand(String name, boolean raining, boolean thundering, String successMessage) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .executes(context -> weather(context, DEFAULT_WEATHER_SECONDS, raining, thundering, successMessage))
            .then(Commands.argument(SECONDS_ARGUMENT, IntegerArgumentType.integer(1))
                .executes(context -> weather(context, getInteger(context, SECONDS_ARGUMENT), raining, thundering, successMessage)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> broadcastCommand(String name) {
        return Commands.literal(name)
            .requires(permissions::admin)
            .then(Commands.argument(MESSAGE_ARGUMENT, StringArgumentType.greedyString())
                .executes(this::broadcast));
    }

    private int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSystemMessage(Messages.header("Komutlar"));
        source.sendSystemMessage(Messages.line("Teleport", "/spawn | /home [name] | /warp [name] | /back | /tpa \"Character Name\" | /tpaccept"));
        source.sendSystemMessage(Messages.line("Admin TP", "/tp <target> [destination] | /tphere <target> | /tpall [target] | /tppos <x y z> [dimension] | /top"));
        source.sendSystemMessage(Messages.line("Homes/warps", "/sethome [name] | /delhome <name> | /homes | /setwarp <name> | /delwarp <name> | /warps"));
        source.sendSystemMessage(Messages.line("Player", "/heal [target] | /feed [target] | /fly [target] [true|false] | /god [target] [true|false] | /speed [walk|fly] <0-10> [target] | /gm <mode> [target]"));
        source.sendSystemMessage(Messages.line("Tools", "/ci [target] | /repair [target] | /hat | /ec [target] | /ext [target] | /near [radius] | /day | /night | /sun | /storm | /broadcast <message>"));
        source.sendSystemMessage(Messages.line("Social", "/msg <player> <message> | /r <message> | /ignore [player] | /mail read | /mail send <player> <message> | /afk [message] | /nick <name|off>"));
        return 1;
    }

    private int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSystemMessage(Messages.header("Durum"));
        source.sendSystemMessage(Messages.line("Spawn", storage.spawn().map(StoredLocation::display).orElse("world spawn")));
        source.sendSystemMessage(Messages.line("Warps", Integer.toString(storage.warps().size())));
        source.sendSystemMessage(Messages.line("Max homes", Integer.toString(config.maxHomesPerPlayer())));
        return 1;
    }

    private int spawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return spawn(context, context.getSource().getPlayerOrException());
    }

    private int spawn(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        backService.record(target);
        storage.spawn().orElseGet(() -> worldSpawn(context.getSource().getServer())).teleport(context.getSource().getServer(), target);
        target.sendSystemMessage(Messages.success("Spawn noktasına ışınlandın."));
        if (context.getSource().getPlayer() != target) {
            context.getSource().sendSystemMessage(Messages.success(displayName(target) + " spawn noktasına ışınlandı."));
        }
        return 1;
    }

    private int setSpawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return setSpawn(context, "default");
    }

    private int setSpawn(CommandContext<CommandSourceStack> context, String group) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        StoredLocation location = StoredLocation.capture(player);
        String normalized = EssentialsStorage.normalizeName(group);
        storage.setSpawn(normalized, location);
        context.getSource().sendSystemMessage(Messages.success("Spawn noktası ayarlandı (" + normalized + "): " + location.display()));
        return 1;
    }

    private int home(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String normalized = EssentialsStorage.normalizeName(name);
        if (normalized.isBlank()) {
            context.getSource().sendSystemMessage(Messages.error("Geçersiz ev adı."));
            return 0;
        }
        return storage.home(player.getUUID(), normalized)
            .map(location -> {
                backService.record(player);
                location.teleport(context.getSource().getServer(), player);
                player.sendSystemMessage(Messages.success("Eve ışınlandın: " + normalized));
                return 1;
            })
            .orElseGet(() -> {
                context.getSource().sendSystemMessage(Messages.error("Ev bulunamadı: " + normalized));
                return 0;
            });
    }

    private int setHome(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String normalized = EssentialsStorage.normalizeName(name);
        if (normalized.isBlank()) {
            context.getSource().sendSystemMessage(Messages.error("Geçersiz ev adı."));
            return 0;
        }
        if (!storage.homes(player.getUUID()).contains(normalized) && storage.homes(player.getUUID()).size() >= config.maxHomesPerPlayer()) {
            context.getSource().sendSystemMessage(Messages.error("Maksimum ev sayısına ulaştın: " + config.maxHomesPerPlayer()));
            return 0;
        }
        storage.setHome(player.getUUID(), normalized, StoredLocation.capture(player));
        context.getSource().sendSystemMessage(Messages.success("Ev ayarlandı: " + normalized));
        return 1;
    }

    private int deleteHome(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String normalized = EssentialsStorage.normalizeName(name);
        if (storage.deleteHome(player.getUUID(), normalized)) {
            context.getSource().sendSystemMessage(Messages.success("Ev silindi: " + normalized));
            return 1;
        }
        context.getSource().sendSystemMessage(Messages.error("Ev bulunamadı: " + normalized));
        return 0;
    }

    private int homes(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        List<String> homes = storage.homes(player.getUUID());
        context.getSource().sendSystemMessage(Messages.line("Homes", homes.isEmpty() ? "none" : String.join(", ", homes)));
        return homes.isEmpty() ? 0 : 1;
    }

    private int warp(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        return warp(context, name, context.getSource().getPlayerOrException());
    }

    private int warp(CommandContext<CommandSourceStack> context, String name, ServerPlayer target) {
        String normalized = EssentialsStorage.normalizeName(name);
        return storage.warp(normalized)
            .map(location -> {
                backService.record(target);
                location.teleport(context.getSource().getServer(), target);
                target.sendSystemMessage(Messages.success("Warp noktasına ışınlandın: " + normalized));
                if (context.getSource().getPlayer() != target) {
                    context.getSource().sendSystemMessage(Messages.success(displayName(target) + " warp noktasına ışınlandı: " + normalized));
                }
                return 1;
            })
            .orElseGet(() -> {
                context.getSource().sendSystemMessage(Messages.error("Warp bulunamadı: " + normalized));
                return 0;
            });
    }

    private int warps(CommandContext<CommandSourceStack> context) {
        List<String> warps = storage.warps();
        context.getSource().sendSystemMessage(Messages.line("Warps", warps.isEmpty() ? "none" : String.join(", ", warps)));
        return warps.isEmpty() ? 0 : 1;
    }

    private int setWarp(CommandContext<CommandSourceStack> context, String name) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String normalized = EssentialsStorage.normalizeName(name);
        if (normalized.isBlank()) {
            context.getSource().sendSystemMessage(Messages.error("Geçersiz warp adı."));
            return 0;
        }
        storage.setWarp(normalized, StoredLocation.capture(player));
        context.getSource().sendSystemMessage(Messages.success("Warp ayarlandı: " + normalized));
        return 1;
    }

    private int deleteWarp(CommandContext<CommandSourceStack> context, String name) {
        String normalized = EssentialsStorage.normalizeName(name);
        if (storage.deleteWarp(normalized)) {
            context.getSource().sendSystemMessage(Messages.success("Warp silindi: " + normalized));
            return 1;
        }
        context.getSource().sendSystemMessage(Messages.error("Warp bulunamadı: " + normalized));
        return 0;
    }

    private int back(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return back(context, context.getSource().getPlayerOrException());
    }

    private int back(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        if (backService.teleportBack(context.getSource().getServer(), target)) {
            if (context.getSource().getPlayer() == target) {
                target.sendSystemMessage(Messages.success("Önceki konuma ışınlandın."));
            } else {
                context.getSource().sendSystemMessage(Messages.success(displayName(target) + " önceki konumuna ışınlandı."));
                target.sendSystemMessage(Messages.success("Önceki konumuna ışınlandın."));
            }
            return 1;
        }
        context.getSource().sendSystemMessage(Messages.error("Önceki konum kaydı yok: " + displayName(target)));
        return 0;
    }

    private int tpa(CommandContext<CommandSourceStack> context, TpaService.RequestType type) throws CommandSyntaxException {
        ServerPlayer requester = context.getSource().getPlayerOrException();
        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
        if (target == null) {
            return 0;
        }
        TpaService.RequestResult result = tpaService.request(context.getSource().getServer(), requester, target, type);
        return switch (result.status()) {
            case SENT -> {
                target.sendSystemMessage(result.request().targetMessage());
                requester.sendSystemMessage(Messages.success("Işınlanma isteği gönderildi: " + displayName(target)));
                yield 1;
            }
            case AUTO_ACCEPTED -> 1;
            case SELF -> {
                requester.sendSystemMessage(Messages.error("Kendine ışınlanma isteği gönderemezsin."));
                yield 0;
            }
            case TARGET_TELEPORT_DISABLED -> {
                requester.sendSystemMessage(Messages.error(displayName(target) + " ışınlanma isteklerini kapatmış."));
                yield 0;
            }
            case DUPLICATE -> {
                requester.sendSystemMessage(Messages.error("Bu oyuncuya zaten bekleyen bir ışınlanma isteğin var: " + displayName(target)));
                yield 0;
            }
        };
    }

    private int tpaall(CommandContext<CommandSourceStack> context, ServerPlayer destination) {
        int sent = 0;
        int autoAccepted = 0;
        int skipped = 0;
        int duplicate = 0;
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            if (player == destination) {
                continue;
            }
            TpaService.RequestResult result = tpaService.request(
                context.getSource().getServer(),
                destination,
                player,
                TpaService.RequestType.HERE
            );
            switch (result.status()) {
                case SENT -> {
                    player.sendSystemMessage(result.request().targetMessage());
                    sent++;
                }
                case AUTO_ACCEPTED -> autoAccepted++;
                case DUPLICATE -> duplicate++;
                default -> skipped++;
            }
        }
        context.getSource().sendSystemMessage(Messages.success(
            "Tpaall: " + sent + " istek gönderildi, " + autoAccepted + " otomatik kabul, " + duplicate + " zaten bekliyor, " + skipped + " atlandı."
        ));
        return sent + autoAccepted;
    }

    private int tpaccept(CommandContext<CommandSourceStack> context, String rawRequester) throws CommandSyntaxException {
        ServerPlayer target = context.getSource().getPlayerOrException();
        if (rawRequester != null && isWildcard(rawRequester)) {
            TpaService.BulkAcceptResult result = tpaService.acceptAll(context.getSource().getServer(), target);
            if (result.accepted() == 0) {
                target.sendSystemMessage(Messages.error("Bekleyen ışınlanma isteği yok."));
                return 0;
            }
            target.sendSystemMessage(Messages.success("Tüm ışınlanma istekleri kabul edildi: " + result.accepted()));
            return result.accepted();
        }

        TpaService.AcceptResult result;
        if (rawRequester == null) {
            result = tpaService.accept(context.getSource().getServer(), target);
        } else {
            ServerPlayer requester = resolveTargetName(context, rawRequester);
            if (requester == null) {
                return 0;
            }
            result = tpaService.accept(context.getSource().getServer(), target, requester);
        }
        return reportTpacceptResult(target, result);
    }

    private int tpdeny(CommandContext<CommandSourceStack> context, String rawRequester) throws CommandSyntaxException {
        ServerPlayer target = context.getSource().getPlayerOrException();
        if (rawRequester != null && isWildcard(rawRequester)) {
            List<TpaService.TeleportRequest> denied = tpaService.denyAll(target);
            denied.forEach(request -> notifyDenied(context, target, request));
            if (denied.isEmpty()) {
                target.sendSystemMessage(Messages.error("Bekleyen ışınlanma isteği yok."));
                return 0;
            }
            target.sendSystemMessage(Messages.success("Tüm ışınlanma istekleri reddedildi: " + denied.size()));
            return denied.size();
        }

        java.util.Optional<TpaService.TeleportRequest> request;
        if (rawRequester == null) {
            request = tpaService.deny(target);
        } else {
            ServerPlayer requester = resolveTargetName(context, rawRequester);
            if (requester == null) {
                return 0;
            }
            request = tpaService.deny(target, requester);
        }
        return request
            .map(deniedRequest -> {
                notifyDenied(context, target, deniedRequest);
                target.sendSystemMessage(Messages.success("Işınlanma isteği reddedildi."));
                return 1;
            })
            .orElseGet(() -> {
                target.sendSystemMessage(Messages.error("Bekleyen ışınlanma isteği yok."));
                return 0;
            });
    }

    private int tpacancel(CommandContext<CommandSourceStack> context, String rawTarget) throws CommandSyntaxException {
        ServerPlayer requester = context.getSource().getPlayerOrException();
        if (rawTarget == null || isWildcard(rawTarget)) {
            List<TpaService.TeleportRequest> cancelled = tpaService.cancel(requester);
            cancelled.forEach(request -> notifyCancelled(context, requester, request));
            if (cancelled.isEmpty()) {
                requester.sendSystemMessage(Messages.error("İptal edilecek ışınlanma isteği yok."));
                return 0;
            }
            requester.sendSystemMessage(Messages.success("Işınlanma istekleri iptal edildi: " + cancelled.size()));
            return cancelled.size();
        }

        ServerPlayer target = resolveTargetName(context, rawTarget);
        if (target == null) {
            return 0;
        }
        return tpaService.cancel(requester, target)
            .map(request -> {
                notifyCancelled(context, requester, request);
                requester.sendSystemMessage(Messages.success("Işınlanma isteği iptal edildi: " + displayName(target)));
                return 1;
            })
            .orElseGet(() -> {
                requester.sendSystemMessage(Messages.error("Bu oyuncuya bekleyen ışınlanma isteğin yok: " + displayName(target)));
                return 0;
            });
    }

    private int teleportState(
        CommandContext<CommandSourceStack> context,
        boolean auto,
        String rawTargetOrState,
        String rawState
    ) throws CommandSyntaxException {
        ServerPlayer target;
        Boolean enabled;
        Boolean firstArgumentState = rawTargetOrState == null ? null : parseToggle(rawTargetOrState);
        if (rawTargetOrState == null || (rawState == null && firstArgumentState != null)) {
            target = context.getSource().getPlayerOrException();
            enabled = firstArgumentState;
        } else {
            if (!permissions.admin(context.getSource())) {
                context.getSource().sendSystemMessage(Messages.error("Başka oyuncular için bu komutu kullanamazsın."));
                return 0;
            }
            target = resolveTargetName(context, rawTargetOrState);
            if (target == null) {
                return 0;
            }
            enabled = rawState == null ? null : parseToggle(rawState);
            if (rawState != null && enabled == null) {
                context.getSource().sendSystemMessage(Messages.error("Geçersiz durum: " + rawState));
                return 0;
            }
        }

        TeleportStateService.ToggleResult result = auto
            ? (enabled == null ? tpaService.toggleAutoTeleportEnabled(target) : tpaService.setAutoTeleportEnabled(target, enabled))
            : (enabled == null ? tpaService.toggleTeleportEnabled(target) : tpaService.setTeleportEnabled(target, enabled));
        reportTeleportState(context, target, auto, result.enabled());
        return 1;
    }

    private int reportTpacceptResult(ServerPlayer target, TpaService.AcceptResult result) {
        return switch (result) {
            case ACCEPTED -> 1;
            case MISSING -> {
                target.sendSystemMessage(Messages.error("Bekleyen ışınlanma isteği yok."));
                yield 0;
            }
            case EXPIRED -> {
                target.sendSystemMessage(Messages.error("Işınlanma isteğinin süresi dolmuş."));
                yield 0;
            }
            case REQUESTER_OFFLINE -> {
                target.sendSystemMessage(Messages.error("İstek sahibi çevrimdışı."));
                yield 0;
            }
        };
    }

    private void notifyDenied(
        CommandContext<CommandSourceStack> context,
        ServerPlayer target,
        TpaService.TeleportRequest request
    ) {
        ServerPlayer requester = context.getSource().getServer().getPlayerList().getPlayer(request.requesterUuid());
        if (requester != null) {
            requester.sendSystemMessage(Messages.error(displayName(target) + " ışınlanma isteğini reddetti."));
        }
    }

    private void notifyCancelled(
        CommandContext<CommandSourceStack> context,
        ServerPlayer requester,
        TpaService.TeleportRequest request
    ) {
        ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayer(request.targetUuid());
        if (target != null) {
            target.sendSystemMessage(Messages.error(displayName(requester) + " ışınlanma isteğini iptal etti."));
        }
    }

    private void reportTeleportState(
        CommandContext<CommandSourceStack> context,
        ServerPlayer target,
        boolean auto,
        boolean enabled
    ) {
        String label = auto ? "Otomatik ışınlanma" : "Işınlanma";
        String state = enabled ? "açıldı" : "kapandı";
        ServerPlayer sender = context.getSource().getPlayer();
        target.sendSystemMessage(Messages.success(label + " " + state + "."));
        if (sender != target) {
            context.getSource().sendSystemMessage(Messages.success(label + " " + state + ": " + displayName(target)));
        }
        if (auto && enabled && !tpaService.isTeleportEnabled(target)) {
            target.sendSystemMessage(Messages.error("Işınlanma kapalıyken otomatik kabul çalışmaz. /tptoggle ile açabilirsin."));
        }
    }

    private static Boolean parseToggle(String rawValue) {
        String value = rawValue.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "on", "enable", "enabled", "1" -> true;
            case "false", "off", "disable", "disabled", "0" -> false;
            default -> null;
        };
    }

    private static boolean isWildcard(String rawValue) {
        String value = rawValue.trim().toLowerCase(Locale.ROOT);
        return value.equals("*") || value.equals("all");
    }

    private static Double parseDouble(String rawValue) {
        try {
            return Double.parseDouble(rawValue);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private int teleportSelfToTarget(CommandContext<CommandSourceStack> context, boolean bypassToggle) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
        if (target == null) {
            return 0;
        }
        if (!ensureTeleportEnabled(context, target, bypassToggle)) {
            return 0;
        }
        teleportPlayerToPlayer(context, player, target);
        context.getSource().sendSystemMessage(Messages.success(displayName(target) + " konumuna ışınlandın."));
        return 1;
    }

    private int teleportTargetToTarget(CommandContext<CommandSourceStack> context, boolean bypassToggle) {
        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
        ServerPlayer destination = resolveTarget(context, DESTINATION_ARGUMENT);
        if (target == null || destination == null) {
            return 0;
        }
        if (!ensureTeleportEnabled(context, target, bypassToggle) || !ensureTeleportEnabled(context, destination, bypassToggle)) {
            return 0;
        }
        teleportPlayerToPlayer(context, target, destination);
        context.getSource().sendSystemMessage(Messages.success(displayName(target) + " oyuncusu " + displayName(destination) + " konumuna ışınlandı."));
        if (context.getSource().getPlayer() != target) {
            target.sendSystemMessage(Messages.success(displayName(destination) + " konumuna ışınlandın."));
        }
        return 1;
    }

    private int teleportTargetToPosition(CommandContext<CommandSourceStack> context, ServerLevel level, boolean bypassToggle) {
        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
        if (target == null) {
            return 0;
        }
        if (!ensureTeleportEnabled(context, target, bypassToggle)) {
            return 0;
        }
        Vec3 position = Vec3Argument.getVec3(context, POSITION_ARGUMENT);
        return teleportPlayerToPosition(context, target, level, position);
    }

    private int teleportSelfToPosition(CommandContext<CommandSourceStack> context, ServerLevel level) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Vec3 position = Vec3Argument.getVec3(context, POSITION_ARGUMENT);
        return teleportPlayerToPosition(context, player, level, position);
    }

    private int teleportTargetHere(CommandContext<CommandSourceStack> context, boolean bypassToggle) throws CommandSyntaxException {
        ServerPlayer destination = context.getSource().getPlayerOrException();
        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
        if (target == null) {
            return 0;
        }
        if (!ensureTeleportEnabled(context, target, bypassToggle)) {
            return 0;
        }
        teleportPlayerToPlayer(context, target, destination);
        context.getSource().sendSystemMessage(Messages.success(displayName(target) + " yanına ışınlandı."));
        target.sendSystemMessage(Messages.success(displayName(destination) + " yanına ışınlandın."));
        return 1;
    }

    private int tpall(CommandContext<CommandSourceStack> context, ServerPlayer destination) {
        int moved = 0;
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            if (player == destination) {
                continue;
            }
            teleportPlayerToPlayer(context, player, destination);
            player.sendSystemMessage(Messages.success(displayName(destination) + " yanına ışınlandın."));
            moved++;
        }
        context.getSource().sendSystemMessage(Messages.success(moved + " oyuncu " + displayName(destination) + " yanına ışınlandı."));
        return moved;
    }

    private boolean ensureTeleportEnabled(CommandContext<CommandSourceStack> context, ServerPlayer target, boolean bypassToggle) {
        if (bypassToggle || tpaService.isTeleportEnabled(target)) {
            return true;
        }
        context.getSource().sendSystemMessage(Messages.error(displayName(target) + " ışınlanmayı kapatmış. Bypass için /tpo veya /tpohere kullan."));
        return false;
    }

    private int top(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        BlockPos destinationBlock = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.blockPosition());
        Vec3 destination = new Vec3(destinationBlock.getX() + 0.5D, destinationBlock.getY(), destinationBlock.getZ() + 0.5D);
        AABB movedBox = player.getBoundingBox().move(destination.x - player.getX(), destination.y - player.getY(), destination.z - player.getZ());
        if (!level.noCollision(player, movedBox)) {
            context.getSource().sendSystemMessage(Messages.error("Güvenli üst konum bulunamadı."));
            return 0;
        }
        return teleportPlayerToPosition(context, player, level, destination);
    }

    private int heal(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        playerStateService.heal(target);
        context.getSource().sendSystemMessage(Messages.success("Oyuncu iyileştirildi: " + displayName(target)));
        if (context.getSource().getPlayer() != target) {
            target.sendSystemMessage(Messages.success("İyileştirildin."));
        }
        return 1;
    }

    private int feed(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        playerStateService.feed(target);
        context.getSource().sendSystemMessage(Messages.success("Oyuncu doyuruldu: " + displayName(target)));
        if (context.getSource().getPlayer() != target) {
            target.sendSystemMessage(Messages.success("Doyuruldun."));
        }
        return 1;
    }

    private int fly(CommandContext<CommandSourceStack> context, ServerPlayer target, Boolean enabled) {
        PlayerStateService.OptionalBoolean result = enabled == null
            ? playerStateService.toggleFly(target)
            : new PlayerStateService.OptionalBoolean(playerStateService.setFly(target, enabled), enabled);
        if (!result.available()) {
            context.getSource().sendSystemMessage(Messages.error("Fly komutu yapılandırmada kapalı."));
            return 0;
        }
        context.getSource().sendSystemMessage(Messages.success("Fly " + (result.value() ? "açıldı" : "kapandı") + ": " + displayName(target)));
        return 1;
    }

    private int god(CommandContext<CommandSourceStack> context, ServerPlayer target, Boolean enabled) {
        PlayerStateService.OptionalBoolean result = enabled == null
            ? playerStateService.toggleGod(target)
            : new PlayerStateService.OptionalBoolean(playerStateService.setGod(target, enabled), enabled);
        if (!result.available()) {
            context.getSource().sendSystemMessage(Messages.error("God komutu yapılandırmada kapalı."));
            return 0;
        }
        context.getSource().sendSystemMessage(Messages.success("God " + (result.value() ? "açıldı" : "kapandı") + ": " + displayName(target)));
        return 1;
    }

    private int gamemode(CommandContext<CommandSourceStack> context, ServerPlayer target, GameType gameType) {
        target.setGameMode(gameType);
        context.getSource().sendSystemMessage(Messages.success(displayName(target) + " oyun modu: " + gameType.getName()));
        return 1;
    }

    private int speed(CommandContext<CommandSourceStack> context, ServerPlayer target, double requestedSpeed, SpeedMode requestedMode) {
        SpeedMode mode = requestedMode == null && target.getAbilities().flying ? SpeedMode.FLY : requestedMode == null ? SpeedMode.WALK : requestedMode;
        double speed = mode == SpeedMode.FLY
            ? playerStateService.setFlySpeed(target, requestedSpeed)
            : playerStateService.setWalkSpeed(target, requestedSpeed);
        context.getSource().sendSystemMessage(Messages.success(displayName(target) + " " + mode.label + " hızı: " + speed));
        return 1;
    }

    private int clearInventory(CommandContext<CommandSourceStack> context, ServerPlayer target, String commandText) {
        ServerPlayer sender = context.getSource().getPlayer();
        if (sender != null && playerStateService.requiresClearInventoryConfirmation(sender, commandText)) {
            sender.sendSystemMessage(Messages.error("Envanteri temizlemek için komutu tekrar yaz: " + commandText));
            return 0;
        }
        target.getInventory().clearContent();
        target.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Envanter temizlendi: " + displayName(target)));
        return 1;
    }

    private int clearInventoryConfirmToggle(CommandContext<CommandSourceStack> context, String commandName) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Boolean forcedState = commandName.toLowerCase(Locale.ROOT).endsWith("on")
            ? Boolean.TRUE
            : commandName.toLowerCase(Locale.ROOT).endsWith("off") ? Boolean.FALSE : null;
        boolean enabled = forcedState == null
            ? playerStateService.toggleClearInventoryConfirm(player)
            : playerStateService.setClearInventoryConfirm(player, forcedState);
        player.sendSystemMessage(Messages.success(enabled
            ? "Envanter temizleme onayı açıldı."
            : "Envanter temizleme onayı kapandı."));
        return 1;
    }

    private int repair(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        ItemStack held = target.getMainHandItem();
        if (held.isEmpty()) {
            context.getSource().sendSystemMessage(Messages.error("Tamir edilecek elde eşya yok: " + displayName(target)));
            return 0;
        }
        if (!held.isDamageableItem() || !held.isDamaged()) {
            context.getSource().sendSystemMessage(Messages.error("Eldeki eşya tamir gerektirmiyor: " + displayName(target)));
            return 0;
        }
        held.setDamageValue(0);
        target.containerMenu.broadcastChanges();
        context.getSource().sendSystemMessage(Messages.success("Eldeki eşya tamir edildi: " + displayName(target)));
        return 1;
    }

    private int hat(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack hand = player.getInventory().getSelectedItem();
        if (hand.isEmpty()) {
            player.sendSystemMessage(Messages.error("Şapka yapmak için elinde eşya olmalı."));
            return 0;
        }
        ItemStack oldHead = player.getItemBySlot(EquipmentSlot.HEAD);
        player.setItemSlot(EquipmentSlot.HEAD, hand.copyAndClear());
        player.getInventory().setSelectedItem(oldHead);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        player.sendSystemMessage(Messages.success("Eldeki eşya şapka olarak takıldı."));
        return 1;
    }

    private int removeHat(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (head.isEmpty()) {
            player.sendSystemMessage(Messages.error("Başında çıkarılacak eşya yok."));
            return 0;
        }
        ItemStack removed = head.copy();
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        player.getInventory().add(removed);
        if (!removed.isEmpty()) {
            player.drop(removed, false, false);
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        player.sendSystemMessage(Messages.success("Şapka çıkarıldı."));
        return 1;
    }

    private int enderChest(CommandContext<CommandSourceStack> context, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer viewer = context.getSource().getPlayerOrException();
        viewer.openMenu(new SimpleMenuProvider(
            (syncId, inventory, player) -> ChestMenu.threeRows(syncId, inventory, target.getEnderChestInventory()),
            Component.literal(displayName(target) + " Ender Chest")
        ));
        return 1;
    }

    private int extinguish(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        target.clearFire();
        context.getSource().sendSystemMessage(Messages.success("Ateş söndürüldü: " + displayName(target)));
        return 1;
    }

    private int near(CommandContext<CommandSourceStack> context, double radius) throws CommandSyntaxException {
        return near(context, context.getSource().getPlayerOrException(), radius);
    }

    private int nearTargetOrRadius(CommandContext<CommandSourceStack> context, String rawTargetOrRadius, Double radius) throws CommandSyntaxException {
        if (radius == null) {
            Double parsedRadius = parseDouble(rawTargetOrRadius);
            if (parsedRadius != null) {
                if (parsedRadius < 1.0D || parsedRadius > 10000.0D) {
                    context.getSource().sendSystemMessage(Messages.error("Near yarıçapı 1 ile 10000 arasında olmalı."));
                    return 0;
                }
                return near(context, context.getSource().getPlayerOrException(), parsedRadius);
            }
        }

        ServerPlayer target = resolveTargetName(context, rawTargetOrRadius);
        if (target == null) {
            return 0;
        }
        return near(context, target, radius == null ? DEFAULT_NEAR_RADIUS : radius);
    }

    private int near(CommandContext<CommandSourceStack> context, ServerPlayer center, double radius) {
        double radiusSquared = radius * radius;
        List<String> nearby = context.getSource().getServer().getPlayerList().getPlayers().stream()
            .filter(other -> other != center)
            .filter(other -> other.level().dimension().equals(center.level().dimension()))
            .filter(other -> other.distanceToSqr(center) <= radiusSquared)
            .sorted(Comparator.comparingDouble(other -> other.distanceToSqr(center)))
            .map(other -> displayName(other) + " (" + Math.round(Math.sqrt(other.distanceToSqr(center))) + "m)")
            .toList();
        context.getSource().sendSystemMessage(Messages.line("Yakındaki oyuncular: " + displayName(center), nearby.isEmpty() ? "none" : String.join(", ", nearby)));
        return nearby.isEmpty() ? 0 : 1;
    }

    private int ping(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSystemMessage(Component.literal("Pong!"));
        return 1;
    }

    private int pingEcho(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSystemMessage(Component.literal(getString(context, MESSAGE_ARGUMENT)));
        return 1;
    }

    private int getPos(CommandContext<CommandSourceStack> context, ServerPlayer target, ServerPlayer distanceFrom) {
        context.getSource().sendSystemMessage(Messages.line("Current World", target.level().dimension().identifier().toString()));
        context.getSource().sendSystemMessage(Messages.line("X", Integer.toString(target.getBlockX())));
        context.getSource().sendSystemMessage(Messages.line("Y", Integer.toString(target.getBlockY())));
        context.getSource().sendSystemMessage(Messages.line("Z", Integer.toString(target.getBlockZ())));
        context.getSource().sendSystemMessage(Messages.line("Yaw", Float.toString((target.getYRot() + 360.0F) % 360.0F)));
        context.getSource().sendSystemMessage(Messages.line("Pitch", Float.toString(target.getXRot())));
        if (distanceFrom != null && distanceFrom != target && distanceFrom.level().dimension().equals(target.level().dimension())) {
            context.getSource().sendSystemMessage(Messages.line("Distance", Long.toString(Math.round(Math.sqrt(target.distanceToSqr(distanceFrom))))));
        }
        return 1;
    }

    private int depth(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int depth = player.getBlockY() - ((ServerLevel) player.level()).getSeaLevel();
        if (depth > 0) {
            player.sendSystemMessage(Messages.success("You are " + depth + " block(s) above sea level."));
        } else if (depth < 0) {
            player.sendSystemMessage(Messages.success("You are " + (-depth) + " block(s) below sea level."));
        } else {
            player.sendSystemMessage(Messages.success("You are at sea level."));
        }
        return 1;
    }

    private int compass(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int bearing = (int) (player.getYRot() + 180.0F + 360.0F) % 360;
        player.sendSystemMessage(Messages.success("Bearing: " + compassDirection(bearing) + " (" + bearing + " degrees)"));
        return 1;
    }

    private int suicide(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        player.sendSystemMessage(Component.literal("Goodbye cruel world..."));
        context.getSource().getServer().getPlayerList().broadcastSystemMessage(
            Messages.success(displayName(player) + " took their own life."),
            false
        );
        player.kill((ServerLevel) player.level());
        return 1;
    }

    private int kill(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        target.kill((ServerLevel) target.level());
        context.getSource().sendSystemMessage(Messages.success("Killed " + displayName(target) + "."));
        return 1;
    }

    private int sudo(CommandContext<CommandSourceStack> context, String command) {
        ServerPlayer target = resolveTarget(context, TARGET_ARGUMENT);
        if (target == null) {
            return 0;
        }
        if (context.getSource().getPlayer() == target) {
            context.getSource().sendSystemMessage(Messages.error("Kendine sudo uygulanmadı."));
            return 0;
        }

        String trimmedCommand = command.trim();
        if (trimmedCommand.isBlank()) {
            context.getSource().sendSystemMessage(Messages.error("Çalıştırılacak komut boş olamaz."));
            return 0;
        }
        if (trimmedCommand.toLowerCase(Locale.ROOT).startsWith("c:")) {
            String chatMessage = trimmedCommand.substring(2).trim();
            if (chatMessage.isBlank()) {
                context.getSource().sendSystemMessage(Messages.error("Gönderilecek sohbet mesajı boş olamaz."));
                return 0;
            }
            context.getSource().getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("<" + displayName(target) + "> " + chatMessage),
                false
            );
            context.getSource().sendSystemMessage(Messages.success(displayName(target) + " adına sohbet mesajı gönderildi."));
            return 1;
        }

        context.getSource().getServer().getCommands().performPrefixedCommand(target.createCommandSourceStack(), trimmedCommand);
        context.getSource().sendSystemMessage(Messages.success(displayName(target) + " adına komut çalıştırıldı: /" + Commands.trimOptionalPrefix(trimmedCommand)));
        return 1;
    }

    private int broadcastWorld(CommandContext<CommandSourceStack> context, ServerLevel level) {
        String message = getString(context, MESSAGE_ARGUMENT);
        Component component = Messages.header(message);
        int recipients = 0;
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(component);
            recipients++;
        }
        context.getSource().sendSystemMessage(Messages.success("Broadcast sent to " + recipients + " player(s) in " + level.dimension().identifier() + "."));
        VantablackEssentialsMod.LOGGER.info("[broadcastworld:{}] {}", level.dimension().identifier(), message);
        return recipients;
    }

    private int burn(CommandContext<CommandSourceStack> context, ServerPlayer target, int seconds) {
        int fireTicks = (int) Math.max(0L, Math.min((long) seconds * 20L, Integer.MAX_VALUE));
        target.setRemainingFireTicks(fireTicks);
        context.getSource().sendSystemMessage(Messages.success(displayName(target) + " set on fire for " + seconds + " second(s)."));
        return 1;
    }

    private int rest(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        target.getStats().setValue(target, Stats.CUSTOM.get(Stats.TIME_SINCE_REST), 0);
        target.setAirSupply(target.getMaxAirSupply());
        context.getSource().sendSystemMessage(Messages.success(displayName(target) + " rested."));
        if (context.getSource().getPlayer() != target) {
            target.sendSystemMessage(Messages.success("You feel rested."));
        }
        return 1;
    }

    private int listPlayers(CommandContext<CommandSourceStack> context) {
        List<String> names = context.getSource().getServer().getPlayerList().getPlayers().stream()
            .map(EssentialsCommands::displayName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        context.getSource().sendSystemMessage(Messages.line("Online", names.size() + "/" + context.getSource().getServer().getMaxPlayers()));
        context.getSource().sendSystemMessage(Component.literal(names.isEmpty() ? "none" : String.join(", ", names)));
        context.getSource().sendSystemMessage(Messages.line("Scope", "Minecraft runtime only; durable platform identity and character history are backend-owned."));
        return names.size();
    }

    private int gc(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        Runtime runtime = Runtime.getRuntime();
        double averageMspt = server.getAverageTickTimeNanos() / 1_000_000.0D;
        double tps = Math.min(server.tickRateManager().tickrate(), 1000.0D / Math.max(averageMspt, 1.0D));
        context.getSource().sendSystemMessage(Messages.header("Server"));
        context.getSource().sendSystemMessage(Messages.line("Uptime", formatUptime(System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime())));
        context.getSource().sendSystemMessage(Messages.line("TPS", String.format(Locale.ROOT, "%.2f", tps)));
        context.getSource().sendSystemMessage(Messages.line("MSPT", String.format(Locale.ROOT, "%.2f", averageMspt)));
        context.getSource().sendSystemMessage(Messages.line("Memory max", (runtime.maxMemory() / 1024L / 1024L) + " MB"));
        context.getSource().sendSystemMessage(Messages.line("Memory total", (runtime.totalMemory() / 1024L / 1024L) + " MB"));
        context.getSource().sendSystemMessage(Messages.line("Memory free", (runtime.freeMemory() / 1024L / 1024L) + " MB"));
        for (ServerLevel level : server.getAllLevels()) {
            long entities = 0;
            for (Object ignored : level.getAllEntities()) {
                entities++;
            }
            context.getSource().sendSystemMessage(Messages.line(
                level.dimension().identifier().toString(),
                level.getChunkSource().getLoadedChunksCount() + " chunks, " + entities + " entities"
            ));
        }
        return 1;
    }

    private int renameHome(CommandContext<CommandSourceStack> context, String oldName, String newName) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String oldNormalized = EssentialsStorage.normalizeName(oldName);
        String newNormalized = EssentialsStorage.normalizeName(newName);
        if (oldNormalized.isBlank() || newNormalized.isBlank()) {
            player.sendSystemMessage(Messages.error("Invalid home name."));
            return 0;
        }
        if (storage.renameHome(player.getUUID(), oldNormalized, newNormalized)) {
            player.sendSystemMessage(Messages.success("Home renamed: " + oldNormalized + " -> " + newNormalized));
            return 1;
        }
        player.sendSystemMessage(Messages.error("Home not found: " + oldNormalized));
        return 0;
    }

    private int warpInfo(CommandContext<CommandSourceStack> context, String name) {
        String normalized = EssentialsStorage.normalizeName(name);
        return storage.warp(normalized)
            .map(location -> {
                context.getSource().sendSystemMessage(Messages.line("Warp", normalized));
                context.getSource().sendSystemMessage(Messages.line("Location", location.display()));
                return 1;
            })
            .orElseGet(() -> {
                context.getSource().sendSystemMessage(Messages.error("Warp not found: " + normalized));
                return 0;
            });
    }

    private int worldToggle(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel target = player.level().dimension().equals(Level.NETHER)
            ? context.getSource().getServer().overworld()
            : context.getSource().getServer().getLevel(Level.NETHER);
        if (target == null) {
            context.getSource().sendSystemMessage(Messages.error("Target world is not loaded."));
            return 0;
        }
        return world(context, target);
    }

    private int world(CommandContext<CommandSourceStack> context, ServerLevel targetLevel) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Vec3 position = player.position();
        double factor = dimensionScale(player.level().dimension(), targetLevel.dimension());
        Vec3 target = new Vec3(Math.floor(position.x * factor) + 0.5D, position.y, Math.floor(position.z * factor) + 0.5D);
        return teleportPlayerToPosition(context, player, targetLevel, target);
    }

    private int jump(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        HitResult hit = player.pick(200.0D, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
            player.sendSystemMessage(Messages.error("No block in sight."));
            return 0;
        }
        BlockPos block = blockHit.getBlockPos();
        Vec3 target = new Vec3(block.getX() + 0.5D, block.getY() + 1.0D, block.getZ() + 0.5D);
        return teleportPlayerToPosition(context, player, (ServerLevel) player.level(), target);
    }

    private int bottom(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        int x = player.getBlockX();
        int z = player.getBlockZ();
        for (int y = level.getMinY() + 1; y < level.getMaxY() - 1; y++) {
            Vec3 target = new Vec3(x + 0.5D, y, z + 0.5D);
            if (hasTeleportCollision(level, player, target)) {
                continue;
            }
            return teleportPlayerToPosition(context, player, level, target);
        }
        player.sendSystemMessage(Messages.error("No safe bottom location found."));
        return 0;
    }

    private int queryTime(CommandContext<CommandSourceStack> context, ServerLevel level) {
        Holder<WorldClock> clock = defaultClock(context, level);
        if (clock == null) {
            return 0;
        }
        long ticks = level.clockManager().getTotalTicks(clock);
        context.getSource().sendSystemMessage(Messages.line("Time", formatTime(ticks) + " in " + level.dimension().identifier()));
        return 1;
    }

    private int setTime(CommandContext<CommandSourceStack> context, ServerLevel level, String value) {
        Long ticks = parseTimeTicks(value);
        if (ticks == null) {
            context.getSource().sendSystemMessage(Messages.error("Invalid time: " + value));
            return 0;
        }
        Holder<WorldClock> clock = defaultClock(context, level);
        if (clock == null) {
            return 0;
        }
        long current = level.clockManager().getTotalTicks(clock);
        long nextDayBase = current - Math.floorMod(current, 24000L) + 24000L;
        level.clockManager().setTotalTicks(clock, nextDayBase + ticks);
        context.getSource().sendSystemMessage(Messages.success("Time set to " + formatTime(ticks) + " in " + level.dimension().identifier()));
        return 1;
    }

    private int addTime(CommandContext<CommandSourceStack> context, ServerLevel level, int ticks) {
        Holder<WorldClock> clock = defaultClock(context, level);
        if (clock == null) {
            return 0;
        }
        level.clockManager().addTicks(clock, ticks);
        context.getSource().sendSystemMessage(Messages.success("Added " + ticks + " ticks to " + level.dimension().identifier()));
        return 1;
    }

    private int weather(CommandContext<CommandSourceStack> context, int seconds, boolean raining, boolean thundering, String successMessage) {
        int ticks = seconds * 20;
        MinecraftServer server = context.getSource().getServer();
        if (raining) {
            server.setWeatherParameters(0, ticks, true, thundering);
        } else {
            server.setWeatherParameters(ticks, 0, false, false);
        }
        context.getSource().sendSystemMessage(Messages.success(successMessage));
        return 1;
    }

    private int weatherMode(CommandContext<CommandSourceStack> context, String mode, int seconds) {
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "storm", "rain" -> weather(context, seconds, true, false, "Yağmur başladı.");
            case "sun", "clear", "sky" -> weather(context, seconds, false, false, "Hava açıldı.");
            default -> {
                context.getSource().sendSystemMessage(Messages.error("Geçersiz hava modu: " + mode));
                yield 0;
            }
        };
    }

    private int thunder(CommandContext<CommandSourceStack> context, boolean enabled, int seconds) {
        return weather(context, seconds, enabled, enabled, "Fırtına " + (enabled ? "açıldı." : "kapandı."));
    }

    private int broadcast(CommandContext<CommandSourceStack> context) {
        String message = getString(context, MESSAGE_ARGUMENT);
        Component component = Messages.header(message);
        context.getSource().getServer().getPlayerList().broadcastSystemMessage(component, false);
        VantablackEssentialsMod.LOGGER.info("[broadcast] {}", message);
        return 1;
    }

    private void teleportPlayerToPlayer(CommandContext<CommandSourceStack> context, ServerPlayer player, ServerPlayer destination) {
        backService.record(player);
        StoredLocation.capture(destination).teleport(context.getSource().getServer(), player);
    }

    private int teleportPlayerToPosition(CommandContext<CommandSourceStack> context, ServerPlayer player, ServerLevel level, Vec3 position) {
        BlockPos blockPosition = BlockPos.containing(position);
        if (!Level.isInSpawnableBounds(blockPosition)) {
            context.getSource().sendSystemMessage(Messages.error("Konum dünya sınırları dışında."));
            return 0;
        }
        if (hasTeleportCollision(level, player, position)) {
            context.getSource().sendSystemMessage(Messages.error("Konum güvenli değil."));
            return 0;
        }
        StoredLocation location = new StoredLocation(level.dimension(), position.x, position.y, position.z, player.getYRot(), player.getXRot());
        backService.record(player);
        location.teleport(context.getSource().getServer(), player);
        context.getSource().sendSystemMessage(Messages.success(displayName(player) + " ışınlandı: " + location.display()));
        return 1;
    }

    private static boolean hasTeleportCollision(ServerLevel level, ServerPlayer player, Vec3 position) {
        AABB movedBox = player.getBoundingBox().move(position.x - player.getX(), position.y - player.getY(), position.z - player.getZ());
        return !level.noCollision(player, movedBox);
    }

    private static double dimensionScale(net.minecraft.resources.ResourceKey<Level> from, net.minecraft.resources.ResourceKey<Level> to) {
        if (from.equals(Level.OVERWORLD) && to.equals(Level.NETHER)) {
            return 0.125D;
        }
        if (from.equals(Level.NETHER) && to.equals(Level.OVERWORLD)) {
            return 8.0D;
        }
        return 1.0D;
    }

    private ServerPlayer resolveTarget(CommandContext<CommandSourceStack> context, String argumentName) {
        return resolveTargetName(context, getString(context, argumentName));
    }

    private ServerPlayer resolveTargetName(CommandContext<CommandSourceStack> context, String rawTarget) {
        return PlayerTargets.find(context.getSource().getServer(), rawTarget)
            .orElseGet(() -> {
                context.getSource().sendSystemMessage(Messages.error("Oyuncu bulunamadı: " + rawTarget));
                return null;
            });
    }

    private CompletableFuture<Suggestions> suggestHomes(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return Suggestions.empty();
        }
        return SharedSuggestionProvider.suggest(storage.homes(player.getUUID()), builder);
    }

    private CompletableFuture<Suggestions> suggestWarps(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(storage.warps(), builder);
    }

    private CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(PlayerTargets.suggestions(context.getSource().getServer()), builder);
    }

    private CompletableFuture<Suggestions> suggestPendingRequesters(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return SharedSuggestionProvider.suggest(List.of("*"), builder);
        }
        return SharedSuggestionProvider.suggest(tpaService.pendingRequesterSuggestions(context.getSource().getServer(), player), builder);
    }

    private CompletableFuture<Suggestions> suggestOutgoingTargets(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return Suggestions.empty();
        }
        return SharedSuggestionProvider.suggest(tpaService.outgoingTargetSuggestions(context.getSource().getServer(), player), builder);
    }

    private CompletableFuture<Suggestions> suggestPlayersAndToggleStates(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Set<String> suggestions = new LinkedHashSet<>(List.of("on", "off", "enable", "disable"));
        if (permissions.admin(context.getSource())) {
            suggestions.addAll(PlayerTargets.suggestions(context.getSource().getServer()));
        }
        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private CompletableFuture<Suggestions> suggestToggleStates(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(List.of("on", "off", "enable", "disable"), builder);
    }

    private CompletableFuture<Suggestions> suggestTimes(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(List.of("sunrise", "day", "morning", "noon", "afternoon", "sunset", "night", "midnight"), builder);
    }

    private CompletableFuture<Suggestions> suggestWeatherModes(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(List.of("storm", "sun", "rain", "clear"), builder);
    }

    private static String displayName(ServerPlayer player) {
        return PlayerTargets.displayName(player);
    }

    private static Holder<WorldClock> defaultClock(CommandContext<CommandSourceStack> context, ServerLevel level) {
        return level.dimensionType().defaultClock()
            .orElseGet(() -> {
                context.getSource().sendSystemMessage(Messages.error("This dimension has no default clock: " + level.dimension().identifier()));
                return null;
            });
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
            if (value.matches("^[0-9]{2}:?[0-9]{2}$")) {
                String digits = value.replace(":", "");
                return hoursMinutesToTicks(Integer.parseInt(digits.substring(0, 2)), Integer.parseInt(digits.substring(2, 4)));
            }
            if (value.matches("^[0-9]{1,2}(:?[0-9]{2})?(am|pm)$")) {
                String digits = value.replaceAll("[^0-9]", "");
                int hours;
                int minutes = 0;
                if (digits.length() > 2) {
                    hours = Integer.parseInt(digits.substring(0, digits.length() - 2));
                    minutes = Integer.parseInt(digits.substring(digits.length() - 2));
                } else {
                    hours = Integer.parseInt(digits);
                }
                if (value.endsWith("pm") && hours != 12) {
                    hours += 12;
                }
                if (value.endsWith("am") && hours == 12) {
                    hours = 0;
                }
                return hoursMinutesToTicks(hours, minutes);
            }
            return null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static long hoursMinutesToTicks(int hours, int minutes) {
        long ticks = 18000L + (hours * 1000L) + Math.round((minutes / 60.0D) * 1000.0D);
        return Math.floorMod(ticks, 24000L);
    }

    private static String formatTime(long ticks) {
        return Math.floorMod(ticks, 24000L) + " ticks";
    }

    private static String formatUptime(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private static String compassDirection(int bearing) {
        if (bearing < 23) {
            return "North";
        } else if (bearing < 68) {
            return "Northeast";
        } else if (bearing < 113) {
            return "East";
        } else if (bearing < 158) {
            return "Southeast";
        } else if (bearing < 203) {
            return "South";
        } else if (bearing < 248) {
            return "Southwest";
        } else if (bearing < 293) {
            return "West";
        } else if (bearing < 338) {
            return "Northwest";
        }
        return "North";
    }

    private static StoredLocation worldSpawn(MinecraftServer server) {
        RespawnData spawn = server.overworld().getRespawnData();
        return new StoredLocation(
            spawn.dimension(),
            spawn.pos().getX() + 0.5D,
            spawn.pos().getY(),
            spawn.pos().getZ() + 0.5D,
            spawn.yaw(),
            spawn.pitch()
        );
    }

    private enum SpeedMode {
        WALK("yürüme"),
        FLY("uçuş");

        private final String label;

        SpeedMode(String label) {
            this.label = label;
        }
    }
}
