package com.vantablack4.essentials;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

public final class PlayerStateService {
    private static final float DEFAULT_WALK_SPEED = 0.1F;
    private static final float DEFAULT_FLY_SPEED = 0.05F;
    private static final double MAX_ESSENTIALS_SPEED = 10.0D;

    private final EssentialsConfig config;
    private final Map<UUID, Boolean> godMode = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> clearInventoryConfirm = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingClearInventoryCommands = new ConcurrentHashMap<>();

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

    public double setWalkSpeed(ServerPlayer player, double speed) {
        double clamped = clampEssentialsSpeed(speed);
        player.getAbilities().setWalkingSpeed((float) (DEFAULT_WALK_SPEED * clamped));
        player.onUpdateAbilities();
        return clamped;
    }

    public double setFlySpeed(ServerPlayer player, double speed) {
        double clamped = clampEssentialsSpeed(speed);
        player.getAbilities().setFlyingSpeed((float) (DEFAULT_FLY_SPEED * clamped));
        player.onUpdateAbilities();
        return clamped;
    }

    public boolean toggleClearInventoryConfirm(ServerPlayer player) {
        return setClearInventoryConfirm(player, !isClearInventoryConfirmEnabled(player));
    }

    public boolean setClearInventoryConfirm(ServerPlayer player, boolean enabled) {
        clearInventoryConfirm.put(player.getUUID(), enabled);
        pendingClearInventoryCommands.remove(player.getUUID());
        return enabled;
    }

    public boolean requiresClearInventoryConfirmation(ServerPlayer player, String commandText) {
        if (!isClearInventoryConfirmEnabled(player)) {
            return false;
        }
        UUID uuid = player.getUUID();
        String previousCommand = pendingClearInventoryCommands.put(uuid, commandText);
        if (commandText.equals(previousCommand)) {
            pendingClearInventoryCommands.remove(uuid);
            return false;
        }
        return true;
    }

    private boolean isClearInventoryConfirmEnabled(ServerPlayer player) {
        return clearInventoryConfirm.getOrDefault(player.getUUID(), false);
    }

    private static boolean canNaturallyFly(ServerPlayer player) {
        GameType gameType = player.gameMode();
        return gameType != GameType.SURVIVAL && gameType != GameType.ADVENTURE;
    }

    private static double clampEssentialsSpeed(double speed) {
        if (Double.isNaN(speed) || Double.isInfinite(speed)) {
            return 1.0D;
        }
        return Math.max(0.0D, Math.min(MAX_ESSENTIALS_SPEED, speed));
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
