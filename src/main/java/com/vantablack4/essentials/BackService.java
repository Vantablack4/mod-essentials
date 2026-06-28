package com.vantablack4.essentials;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class BackService {
    private final Map<UUID, StoredLocation> previousLocations = new ConcurrentHashMap<>();

    public void record(ServerPlayer player) {
        previousLocations.put(player.getUUID(), StoredLocation.capture(player));
    }

    public Optional<StoredLocation> previous(ServerPlayer player) {
        return Optional.ofNullable(previousLocations.get(player.getUUID()));
    }

    public boolean teleportBack(MinecraftServer server, ServerPlayer player) {
        StoredLocation previous = previousLocations.get(player.getUUID());
        if (previous == null) {
            return false;
        }
        StoredLocation current = StoredLocation.capture(player);
        previous.teleport(server, player);
        previousLocations.put(player.getUUID(), current);
        return true;
    }
}
