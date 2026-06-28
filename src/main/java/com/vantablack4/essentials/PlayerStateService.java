package com.vantablack4.essentials;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

public final class PlayerStateService {
    private final EssentialsConfig config;
    private final Map<UUID, Boolean> godMode = new ConcurrentHashMap<>();

    public PlayerStateService(EssentialsConfig config) {
        this.config = config;
    }

    public boolean heal(ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        player.clearFire();
        if (config.removeEffectsOnHeal()) {
            player.removeAllEffects();
        }
        return true;
    }

    public boolean feed(ServerPlayer player) {
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
        return true;
    }

    public boolean setFly(ServerPlayer player, boolean enabled) {
        if (!config.runtimeFlyAndGod()) {
            return false;
        }
        player.getAbilities().mayfly = enabled || canNaturallyFly(player);
        if (!player.getAbilities().mayfly) {
            player.getAbilities().flying = false;
        }
        if (enabled) {
            player.getAbilities().flying = true;
        }
        player.onUpdateAbilities();
        return true;
    }

    public OptionalBoolean toggleFly(ServerPlayer player) {
        if (!config.runtimeFlyAndGod()) {
            return OptionalBoolean.disabled();
        }
        boolean enabled = !player.getAbilities().mayfly || !player.getAbilities().flying;
        setFly(player, enabled);
        return OptionalBoolean.enabled(enabled);
    }

    public boolean setGod(ServerPlayer player, boolean enabled) {
        if (!config.runtimeFlyAndGod()) {
            return false;
        }
        player.getAbilities().invulnerable = enabled || canNaturallyFly(player);
        player.onUpdateAbilities();
        godMode.put(player.getUUID(), enabled);
        return true;
    }

    public OptionalBoolean toggleGod(ServerPlayer player) {
        if (!config.runtimeFlyAndGod()) {
            return OptionalBoolean.disabled();
        }
        boolean enabled = !godMode.getOrDefault(player.getUUID(), false);
        setGod(player, enabled);
        return OptionalBoolean.enabled(enabled);
    }

    private static boolean canNaturallyFly(ServerPlayer player) {
        GameType gameType = player.gameMode();
        return gameType != GameType.SURVIVAL && gameType != GameType.ADVENTURE;
    }

    public record OptionalBoolean(boolean available, boolean value) {
        static OptionalBoolean enabled(boolean value) {
            return new OptionalBoolean(true, value);
        }

        static OptionalBoolean disabled() {
            return new OptionalBoolean(false, false);
        }
    }
}
