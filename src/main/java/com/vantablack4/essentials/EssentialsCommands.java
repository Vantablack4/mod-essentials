package com.vantablack4.essentials;

import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelData.RespawnData;

public final class EssentialsCommands {
    private static final String NAME_ARGUMENT = "name";
    private static final String TARGET_ARGUMENT = "target";
    private static final String MESSAGE_ARGUMENT = "message";
    private static final String ENABLED_ARGUMENT = "enabled";

    private final EssentialsConfig config;
    private final EssentialsStorage storage;
    private final BackService backService;
    private final TpaService tpaService;
    private final PlayerStateService playerStateService;
    private final PermissionChecks permissions;

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
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(server -> tpaService.pruneExpired());
    }

    private void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(root("vessentials"));
        dispatcher.register(root("essentials"));
        dispatcher.register(Commands.literal("spawn").executes(this::spawn));
        dispatcher.register(Commands.literal("setspawn").requires(permissions::admin).executes(this::setSpawn));
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
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestWarps)
                .executes(context -> warp(context, getString(context, NAME_ARGUMENT)))));
        dispatcher.register(Commands.literal("warps").executes(this::warps));
        dispatcher.register(Commands.literal("setwarp").requires(permissions::admin)
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .executes(context -> setWarp(context, getString(context, NAME_ARGUMENT)))));
        dispatcher.register(Commands.literal("delwarp").requires(permissions::admin)
            .then(Commands.argument(NAME_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestWarps)
                .executes(context -> deleteWarp(context, getString(context, NAME_ARGUMENT)))));
        dispatcher.register(Commands.literal("back").executes(this::back));
        dispatcher.register(Commands.literal("tpa")
            .then(Commands.argument(TARGET_ARGUMENT, EntityArgument.player())
                .executes(context -> tpa(context, TpaService.RequestType.TO))));
        dispatcher.register(Commands.literal("tpahere")
            .then(Commands.argument(TARGET_ARGUMENT, EntityArgument.player())
                .executes(context -> tpa(context, TpaService.RequestType.HERE))));
        dispatcher.register(Commands.literal("tpaccept").executes(this::tpaccept));
        dispatcher.register(Commands.literal("tpdeny").executes(this::tpdeny));
        dispatcher.register(Commands.literal("tpacancel").executes(this::tpacancel));
        dispatcher.register(Commands.literal("heal").requires(permissions::admin)
            .executes(context -> heal(context, context.getSource().getPlayerOrException()))
            .then(Commands.argument(TARGET_ARGUMENT, EntityArgument.player())
                .executes(context -> heal(context, EntityArgument.getPlayer(context, TARGET_ARGUMENT)))));
        dispatcher.register(Commands.literal("feed").requires(permissions::admin)
            .executes(context -> feed(context, context.getSource().getPlayerOrException()))
            .then(Commands.argument(TARGET_ARGUMENT, EntityArgument.player())
                .executes(context -> feed(context, EntityArgument.getPlayer(context, TARGET_ARGUMENT)))));
        dispatcher.register(Commands.literal("fly").requires(permissions::admin)
            .executes(context -> fly(context, context.getSource().getPlayerOrException(), null))
            .then(Commands.argument(TARGET_ARGUMENT, EntityArgument.player())
                .executes(context -> fly(context, EntityArgument.getPlayer(context, TARGET_ARGUMENT), null))
                .then(Commands.argument(ENABLED_ARGUMENT, BoolArgumentType.bool())
                    .executes(context -> fly(context, EntityArgument.getPlayer(context, TARGET_ARGUMENT), getBool(context, ENABLED_ARGUMENT))))));
        dispatcher.register(Commands.literal("god").requires(permissions::admin)
            .executes(context -> god(context, context.getSource().getPlayerOrException(), null))
            .then(Commands.argument(TARGET_ARGUMENT, EntityArgument.player())
                .executes(context -> god(context, EntityArgument.getPlayer(context, TARGET_ARGUMENT), null))
                .then(Commands.argument(ENABLED_ARGUMENT, BoolArgumentType.bool())
                    .executes(context -> god(context, EntityArgument.getPlayer(context, TARGET_ARGUMENT), getBool(context, ENABLED_ARGUMENT))))));
        dispatcher.register(Commands.literal("broadcast").requires(permissions::admin)
            .then(Commands.argument(MESSAGE_ARGUMENT, StringArgumentType.greedyString())
                .executes(this::broadcast)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return Commands.literal(name)
            .executes(this::status)
            .then(Commands.literal("help").executes(this::help))
            .then(Commands.literal("status").executes(this::status));
    }

    private int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSystemMessage(Messages.header("Komutlar"));
        source.sendSystemMessage(Messages.line("Teleport", "/spawn | /home [name] | /warp <name> | /back | /tpa <player>"));
        source.sendSystemMessage(Messages.line("Homes", "/sethome [name] | /delhome <name> | /homes"));
        source.sendSystemMessage(Messages.line("Admin", "/setspawn | /setwarp <name> | /delwarp <name> | /heal [player] | /feed [player] | /fly [player] [true|false] | /god [player] [true|false] | /broadcast <message>"));
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
        ServerPlayer player = context.getSource().getPlayerOrException();
        backService.record(player);
        storage.spawn().orElseGet(() -> worldSpawn(context.getSource().getServer())).teleport(context.getSource().getServer(), player);
        player.sendSystemMessage(Messages.success("Spawn noktasına ışınlandın."));
        return 1;
    }

    private int setSpawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        StoredLocation location = StoredLocation.capture(player);
        storage.setSpawn(location);
        context.getSource().sendSystemMessage(Messages.success("Spawn noktası ayarlandı: " + location.display()));
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
        ServerPlayer player = context.getSource().getPlayerOrException();
        String normalized = EssentialsStorage.normalizeName(name);
        return storage.warp(normalized)
            .map(location -> {
                backService.record(player);
                location.teleport(context.getSource().getServer(), player);
                player.sendSystemMessage(Messages.success("Warp noktasına ışınlandın: " + normalized));
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
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (backService.teleportBack(context.getSource().getServer(), player)) {
            player.sendSystemMessage(Messages.success("Önceki konuma ışınlandın."));
            return 1;
        }
        player.sendSystemMessage(Messages.error("Önceki konum kaydı yok."));
        return 0;
    }

    private int tpa(CommandContext<CommandSourceStack> context, TpaService.RequestType type) throws CommandSyntaxException {
        ServerPlayer requester = context.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(context, TARGET_ARGUMENT);
        if (requester == target) {
            requester.sendSystemMessage(Messages.error("Kendine ışınlanma isteği gönderemezsin."));
            return 0;
        }
        TpaService.TeleportRequest request = tpaService.request(requester, target, type);
        target.sendSystemMessage(request.targetMessage());
        requester.sendSystemMessage(Messages.success("Işınlanma isteği gönderildi: " + target.getName().getString()));
        return 1;
    }

    private int tpaccept(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer target = context.getSource().getPlayerOrException();
        return switch (tpaService.accept(context.getSource().getServer(), target)) {
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

    private int tpdeny(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer target = context.getSource().getPlayerOrException();
        return tpaService.deny(target)
            .map(request -> {
                target.sendSystemMessage(Messages.success("Işınlanma isteği reddedildi."));
                ServerPlayer requester = context.getSource().getServer().getPlayerList().getPlayer(request.requesterUuid());
                if (requester != null) {
                    requester.sendSystemMessage(Messages.error(target.getName().getString() + " ışınlanma isteğini reddetti."));
                }
                return 1;
            })
            .orElseGet(() -> {
                target.sendSystemMessage(Messages.error("Bekleyen ışınlanma isteği yok."));
                return 0;
            });
    }

    private int tpacancel(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer requester = context.getSource().getPlayerOrException();
        return tpaService.cancel(requester)
            .map(request -> {
                requester.sendSystemMessage(Messages.success("Işınlanma isteği iptal edildi."));
                ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayer(request.targetUuid());
                if (target != null) {
                    target.sendSystemMessage(Messages.error(requester.getName().getString() + " ışınlanma isteğini iptal etti."));
                }
                return 1;
            })
            .orElseGet(() -> {
                requester.sendSystemMessage(Messages.error("İptal edilecek ışınlanma isteği yok."));
                return 0;
            });
    }

    private int heal(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        playerStateService.heal(target);
        context.getSource().sendSystemMessage(Messages.success("Oyuncu iyileştirildi: " + target.getName().getString()));
        if (context.getSource().getPlayer() != target) {
            target.sendSystemMessage(Messages.success("İyileştirildin."));
        }
        return 1;
    }

    private int feed(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        playerStateService.feed(target);
        context.getSource().sendSystemMessage(Messages.success("Oyuncu doyuruldu: " + target.getName().getString()));
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
        context.getSource().sendSystemMessage(Messages.success("Fly " + (result.value() ? "açıldı" : "kapandı") + ": " + target.getName().getString()));
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
        context.getSource().sendSystemMessage(Messages.success("God " + (result.value() ? "açıldı" : "kapandı") + ": " + target.getName().getString()));
        return 1;
    }

    private int broadcast(CommandContext<CommandSourceStack> context) {
        String message = getString(context, MESSAGE_ARGUMENT);
        Component component = Messages.header(message);
        context.getSource().getServer().getPlayerList().broadcastSystemMessage(component, false);
        VantablackEssentialsMod.LOGGER.info("[broadcast] {}", message);
        return 1;
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
}
