package com.vantablack4.essentials.commands.info;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class InfoPlayerSessions {
    private final Map<UUID, SessionRecord> records = new ConcurrentHashMap<>();
    private final AtomicBoolean registered = new AtomicBoolean();

    void register() {
        if (!registered.compareAndSet(false, true)) {
            return;
        }

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> markOnline(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> markOffline(handler.player));
    }

    void observe(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            markOnline(player);
        }
    }

    SessionRecord markOnline(ServerPlayer player) {
        Instant now = Instant.now();
        return records.compute(player.getUUID(), (uuid, previous) -> {
            Instant firstSeen = previous == null ? now : previous.firstSeen();
            Instant sessionStart = previous != null && previous.online() ? previous.currentSessionStartedAt() : now;
            return new SessionRecord(
                player.getUUID(),
                player.getScoreboardName(),
                InfoPlayerLookup.displayName(player),
                firstSeen,
                sessionStart,
                now,
                true
            );
        });
    }

    void markOffline(ServerPlayer player) {
        Instant now = Instant.now();
        records.compute(player.getUUID(), (uuid, previous) -> {
            Instant firstSeen = previous == null ? now : previous.firstSeen();
            Instant sessionStart = previous == null ? now : previous.currentSessionStartedAt();
            return new SessionRecord(
                player.getUUID(),
                player.getScoreboardName(),
                InfoPlayerLookup.displayName(player),
                firstSeen,
                sessionStart,
                now,
                false
            );
        });
    }

    Optional<SessionRecord> find(String rawTarget) {
        String target = InfoPlayerLookup.cleanInput(rawTarget);
        if (target.isBlank()) {
            return Optional.empty();
        }

        try {
            UUID uuid = UUID.fromString(target);
            return Optional.ofNullable(records.get(uuid));
        } catch (IllegalArgumentException ignored) {
            // Fall through to name matching.
        }

        return records.values().stream()
            .filter(record -> record.accountName().equalsIgnoreCase(target) || record.displayName().equalsIgnoreCase(target))
            .max(Comparator.comparing(SessionRecord::lastSeenAt));
    }

    Collection<SessionRecord> records() {
        return records.values();
    }

    record SessionRecord(
        UUID uuid,
        String accountName,
        String displayName,
        Instant firstSeen,
        Instant currentSessionStartedAt,
        Instant lastSeenAt,
        boolean online
    ) {
    }
}
