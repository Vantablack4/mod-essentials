package com.vantablack4.essentials;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class TpaService {
    public enum RequestType {
        TO,
        HERE
    }

    public enum AcceptResult {
        ACCEPTED,
        MISSING,
        EXPIRED,
        REQUESTER_OFFLINE
    }

    private final EssentialsConfig config;
    private final BackService backService;
    private final Map<UUID, TeleportRequest> pendingByTarget = new ConcurrentHashMap<>();

    public TpaService(EssentialsConfig config, BackService backService) {
        this.config = config;
        this.backService = backService;
    }

    public TeleportRequest request(ServerPlayer requester, ServerPlayer target, RequestType type) {
        TeleportRequest request = new TeleportRequest(
            requester.getUUID(),
            requester.getName().getString(),
            target.getUUID(),
            target.getName().getString(),
            type,
            Instant.now().plus(Duration.ofSeconds(config.teleportRequestTimeoutSeconds()))
        );
        pendingByTarget.put(target.getUUID(), request);
        return request;
    }

    public AcceptResult accept(MinecraftServer server, ServerPlayer target) {
        TeleportRequest request = pendingByTarget.remove(target.getUUID());
        if (request == null) {
            return AcceptResult.MISSING;
        }
        if (request.expired()) {
            return AcceptResult.EXPIRED;
        }

        ServerPlayer requester = server.getPlayerList().getPlayer(request.requesterUuid());
        if (requester == null) {
            return AcceptResult.REQUESTER_OFFLINE;
        }

        if (request.type() == RequestType.TO) {
            backService.record(requester);
            StoredLocation.capture(target).teleport(server, requester);
            requester.sendSystemMessage(Messages.success("Teleport request accepted."));
            target.sendSystemMessage(Messages.success("Teleported " + requester.getName().getString() + " to you."));
        } else {
            backService.record(target);
            StoredLocation.capture(requester).teleport(server, target);
            target.sendSystemMessage(Messages.success("Teleport request accepted."));
            requester.sendSystemMessage(Messages.success("Teleported " + target.getName().getString() + " to you."));
        }
        return AcceptResult.ACCEPTED;
    }

    public Optional<TeleportRequest> deny(ServerPlayer target) {
        return Optional.ofNullable(pendingByTarget.remove(target.getUUID()));
    }

    public Optional<TeleportRequest> cancel(ServerPlayer requester) {
        for (TeleportRequest request : pendingByTarget.values()) {
            if (request.requesterUuid().equals(requester.getUUID())) {
                pendingByTarget.remove(request.targetUuid(), request);
                return Optional.of(request);
            }
        }
        return Optional.empty();
    }

    public void pruneExpired() {
        pendingByTarget.entrySet().removeIf(entry -> entry.getValue().expired());
    }

    public record TeleportRequest(
        UUID requesterUuid,
        String requesterName,
        UUID targetUuid,
        String targetName,
        RequestType type,
        Instant expiresAt
    ) {
        public boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }

        public Component targetMessage() {
            if (type == RequestType.TO) {
                return Messages.line("TPA", requesterName + " wants to teleport to you. Use /tpaccept or /tpdeny.");
            }
            return Messages.line("TPA", requesterName + " wants you to teleport to them. Use /tpaccept or /tpdeny.");
        }
    }
}
