package com.vantablack4.essentials;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;

public final class TeleportStateService {
    private final Map<UUID, TeleportState> states = new ConcurrentHashMap<>();

    public boolean isTeleportEnabled(ServerPlayer player) {
        return state(player).teleportEnabled();
    }

    public ToggleResult setTeleportEnabled(ServerPlayer player, boolean enabled) {
        states.compute(player.getUUID(), (uuid, state) -> {
            TeleportState previous = state == null ? TeleportState.DEFAULT : state;
            return previous.withTeleportEnabled(enabled);
        });
        return new ToggleResult(enabled);
    }

    public ToggleResult toggleTeleportEnabled(ServerPlayer player) {
        TeleportState updated = states.compute(player.getUUID(), (uuid, state) -> {
            TeleportState previous = state == null ? TeleportState.DEFAULT : state;
            return previous.withTeleportEnabled(!previous.teleportEnabled());
        });
        return new ToggleResult(updated.teleportEnabled());
    }

    public boolean isAutoTeleportEnabled(ServerPlayer player) {
        return state(player).autoTeleportEnabled();
    }

    public ToggleResult setAutoTeleportEnabled(ServerPlayer player, boolean enabled) {
        states.compute(player.getUUID(), (uuid, state) -> {
            TeleportState previous = state == null ? TeleportState.DEFAULT : state;
            return previous.withAutoTeleportEnabled(enabled);
        });
        return new ToggleResult(enabled);
    }

    public ToggleResult toggleAutoTeleportEnabled(ServerPlayer player) {
        TeleportState updated = states.compute(player.getUUID(), (uuid, state) -> {
            TeleportState previous = state == null ? TeleportState.DEFAULT : state;
            return previous.withAutoTeleportEnabled(!previous.autoTeleportEnabled());
        });
        return new ToggleResult(updated.autoTeleportEnabled());
    }

    private TeleportState state(ServerPlayer player) {
        return states.getOrDefault(player.getUUID(), TeleportState.DEFAULT);
    }

    public record ToggleResult(boolean enabled) {
    }

    private record TeleportState(boolean teleportEnabled, boolean autoTeleportEnabled) {
        private static final TeleportState DEFAULT = new TeleportState(true, false);

        private TeleportState withTeleportEnabled(boolean enabled) {
            return new TeleportState(enabled, autoTeleportEnabled);
        }

        private TeleportState withAutoTeleportEnabled(boolean enabled) {
            return new TeleportState(teleportEnabled, enabled);
        }
    }
}
