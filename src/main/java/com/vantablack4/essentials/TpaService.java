package com.vantablack4.essentials;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    public enum RequestStatus {
        SENT,
        AUTO_ACCEPTED,
        SELF,
        TARGET_TELEPORT_DISABLED,
        DUPLICATE
    }

    public enum AcceptResult {
        ACCEPTED,
        MISSING,
        EXPIRED,
        REQUESTER_OFFLINE
    }

    private final EssentialsConfig config;
    private final BackService backService;
    private final TeleportStateService teleportState = new TeleportStateService();
    private final Map<UUID, Map<UUID, TeleportRequest>> pendingByTarget = new ConcurrentHashMap<>();

    public TpaService(EssentialsConfig config, BackService backService) {
        this.config = config;
        this.backService = backService;
    }

    public RequestResult request(MinecraftServer server, ServerPlayer requester, ServerPlayer target, RequestType type) {
        if (requester == target) {
            return new RequestResult(RequestStatus.SELF, null);
        }
        if (!isTeleportEnabled(target)) {
            return new RequestResult(RequestStatus.TARGET_TELEPORT_DISABLED, null);
        }

        Instant now = Instant.now();
        TeleportRequest request = new TeleportRequest(
            requester.getUUID(),
            displayName(requester),
            target.getUUID(),
            displayName(target),
            type,
            type == RequestType.HERE ? StoredLocation.capture(requester) : null,
            now,
            now.plus(Duration.ofSeconds(config.teleportRequestTimeoutSeconds()))
        );

        Map<UUID, TeleportRequest> targetRequests = requestsFor(target);
        TeleportRequest existing = targetRequests.get(requester.getUUID());
        if (existing != null && !existing.expired() && existing.type() == type) {
            return new RequestResult(RequestStatus.DUPLICATE, existing);
        }
        if (existing != null && existing.expired()) {
            targetRequests.remove(requester.getUUID(), existing);
        }

        if (isAutoTeleportEnabled(target)) {
            targetRequests.remove(requester.getUUID());
            if (targetRequests.isEmpty()) {
                pendingByTarget.remove(target.getUUID(), targetRequests);
            }
            performTeleport(server, target, requester, request, true);
            return new RequestResult(RequestStatus.AUTO_ACCEPTED, request);
        }

        targetRequests.put(requester.getUUID(), request);
        return new RequestResult(RequestStatus.SENT, request);
    }

    public AcceptResult accept(MinecraftServer server, ServerPlayer target) {
        RequestLookup lookup = nextRequest(target);
        return switch (lookup.status()) {
            case FOUND -> accept(server, target, lookup.request(), true);
            case EXPIRED -> AcceptResult.EXPIRED;
            case MISSING -> AcceptResult.MISSING;
        };
    }

    public AcceptResult accept(MinecraftServer server, ServerPlayer target, ServerPlayer requester) {
        Map<UUID, TeleportRequest> requests = pendingByTarget.get(target.getUUID());
        if (requests == null) {
            return AcceptResult.MISSING;
        }
        TeleportRequest request = requests.get(requester.getUUID());
        if (request == null) {
            return AcceptResult.MISSING;
        }
        if (request.expired()) {
            removeRequest(request);
            return AcceptResult.EXPIRED;
        }
        return accept(server, target, request, true);
    }

    public BulkAcceptResult acceptAll(MinecraftServer server, ServerPlayer target) {
        int accepted = 0;
        int skipped = 0;
        for (TeleportRequest request : activeRequests(target)) {
            if (accept(server, target, request, false) == AcceptResult.ACCEPTED) {
                accepted++;
            } else {
                skipped++;
            }
        }
        return new BulkAcceptResult(accepted, skipped);
    }

    public Optional<TeleportRequest> deny(ServerPlayer target) {
        return nextRequest(target)
            .requestOptional()
            .map(request -> {
                removeRequest(request);
                return request;
            });
    }

    public Optional<TeleportRequest> deny(ServerPlayer target, ServerPlayer requester) {
        Map<UUID, TeleportRequest> requests = pendingByTarget.get(target.getUUID());
        if (requests == null) {
            return Optional.empty();
        }
        TeleportRequest request = requests.get(requester.getUUID());
        if (request == null) {
            return Optional.empty();
        }
        removeRequest(request);
        return request.expired() ? Optional.empty() : Optional.of(request);
    }

    public List<TeleportRequest> denyAll(ServerPlayer target) {
        List<TeleportRequest> denied = activeRequests(target);
        denied.forEach(this::removeRequest);
        return denied;
    }

    public List<TeleportRequest> cancel(ServerPlayer requester) {
        List<TeleportRequest> cancelled = outgoingRequests(requester);
        cancelled.forEach(this::removeRequest);
        return cancelled;
    }

    public Optional<TeleportRequest> cancel(ServerPlayer requester, ServerPlayer target) {
        Map<UUID, TeleportRequest> requests = pendingByTarget.get(target.getUUID());
        if (requests == null) {
            return Optional.empty();
        }
        TeleportRequest request = requests.get(requester.getUUID());
        if (request == null) {
            return Optional.empty();
        }
        removeRequest(request);
        return request.expired() ? Optional.empty() : Optional.of(request);
    }

    public boolean isTeleportEnabled(ServerPlayer player) {
        return teleportState.isTeleportEnabled(player);
    }

    public TeleportStateService.ToggleResult setTeleportEnabled(ServerPlayer player, boolean enabled) {
        return teleportState.setTeleportEnabled(player, enabled);
    }

    public TeleportStateService.ToggleResult toggleTeleportEnabled(ServerPlayer player) {
        return teleportState.toggleTeleportEnabled(player);
    }

    public boolean isAutoTeleportEnabled(ServerPlayer player) {
        return teleportState.isAutoTeleportEnabled(player);
    }

    public TeleportStateService.ToggleResult setAutoTeleportEnabled(ServerPlayer player, boolean enabled) {
        return teleportState.setAutoTeleportEnabled(player, enabled);
    }

    public TeleportStateService.ToggleResult toggleAutoTeleportEnabled(ServerPlayer player) {
        return teleportState.toggleAutoTeleportEnabled(player);
    }

    public Set<String> pendingRequesterSuggestions(MinecraftServer server, ServerPlayer target) {
        Set<String> suggestions = new LinkedHashSet<>();
        activeRequests(target).forEach(request -> addPlayerSuggestion(server, suggestions, request.requesterUuid(), request.requesterName()));
        suggestions.add("*");
        return suggestions;
    }

    public Set<String> outgoingTargetSuggestions(MinecraftServer server, ServerPlayer requester) {
        Set<String> suggestions = new LinkedHashSet<>();
        outgoingRequests(requester).forEach(request -> addPlayerSuggestion(server, suggestions, request.targetUuid(), request.targetName()));
        return suggestions;
    }

    private AcceptResult accept(MinecraftServer server, ServerPlayer target, TeleportRequest request, boolean notifyTarget) {
        removeRequest(request);
        ServerPlayer requester = server.getPlayerList().getPlayer(request.requesterUuid());
        if (requester == null) {
            return AcceptResult.REQUESTER_OFFLINE;
        }
        performTeleport(server, target, requester, request, notifyTarget);
        return AcceptResult.ACCEPTED;
    }

    private void performTeleport(
        MinecraftServer server,
        ServerPlayer target,
        ServerPlayer requester,
        TeleportRequest request,
        boolean notifyTarget
    ) {
        if (request.type() == RequestType.TO) {
            backService.record(requester);
            StoredLocation.capture(target).teleport(server, requester);
            requester.sendSystemMessage(Messages.success("Teleport request accepted."));
            if (notifyTarget) {
                target.sendSystemMessage(Messages.success("Teleported " + displayName(requester) + " to you."));
            }
            return;
        }

        backService.record(target);
        StoredLocation destination = request.destinationLocation() == null
            ? StoredLocation.capture(requester)
            : request.destinationLocation();
        destination.teleport(server, target);
        if (notifyTarget) {
            target.sendSystemMessage(Messages.success("Teleport request accepted."));
        }
        requester.sendSystemMessage(Messages.success("Teleported " + displayName(target) + " to your request location."));
    }

    private RequestLookup nextRequest(ServerPlayer target) {
        boolean removedExpired = false;
        for (TeleportRequest request : sortedRequests(target.getUUID())) {
            if (request.expired()) {
                removeRequest(request);
                removedExpired = true;
                continue;
            }
            return RequestLookup.found(request);
        }
        return removedExpired ? RequestLookup.expired() : RequestLookup.missing();
    }

    private List<TeleportRequest> activeRequests(ServerPlayer target) {
        List<TeleportRequest> requests = new ArrayList<>();
        for (TeleportRequest request : sortedRequests(target.getUUID())) {
            if (request.expired()) {
                removeRequest(request);
            } else {
                requests.add(request);
            }
        }
        return requests;
    }

    private List<TeleportRequest> outgoingRequests(ServerPlayer requester) {
        List<TeleportRequest> requests = new ArrayList<>();
        for (Map<UUID, TeleportRequest> targetRequests : pendingByTarget.values()) {
            for (TeleportRequest request : new ArrayList<>(targetRequests.values())) {
                if (request.expired()) {
                    removeRequest(request);
                } else if (request.requesterUuid().equals(requester.getUUID())) {
                    requests.add(request);
                }
            }
        }
        requests.sort(Comparator.comparing(TeleportRequest::requestedAt).reversed());
        return requests;
    }

    private List<TeleportRequest> sortedRequests(UUID targetUuid) {
        Map<UUID, TeleportRequest> requests = pendingByTarget.get(targetUuid);
        if (requests == null) {
            return List.of();
        }
        return requests.values().stream()
            .sorted(Comparator.comparing(TeleportRequest::requestedAt).reversed())
            .toList();
    }

    private Map<UUID, TeleportRequest> requestsFor(ServerPlayer target) {
        return pendingByTarget.computeIfAbsent(target.getUUID(), ignored -> new ConcurrentHashMap<>());
    }

    private void removeRequest(TeleportRequest request) {
        Map<UUID, TeleportRequest> requests = pendingByTarget.get(request.targetUuid());
        if (requests == null) {
            return;
        }
        requests.remove(request.requesterUuid(), request);
        if (requests.isEmpty()) {
            pendingByTarget.remove(request.targetUuid(), requests);
        }
    }

    private static void addPlayerSuggestion(MinecraftServer server, Set<String> suggestions, UUID uuid, String storedName) {
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) {
            suggestions.add(quoteIfNeeded(storedName));
            return;
        }
        suggestions.add(player.getScoreboardName());
        suggestions.add(quoteIfNeeded(displayName(player)));
    }

    public void pruneExpired() {
        pendingByTarget.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(requestEntry -> requestEntry.getValue().expired());
            return entry.getValue().isEmpty();
        });
    }

    public record RequestResult(RequestStatus status, TeleportRequest request) {
    }

    public record BulkAcceptResult(int accepted, int skipped) {
    }

    public record TeleportRequest(
        UUID requesterUuid,
        String requesterName,
        UUID targetUuid,
        String targetName,
        RequestType type,
        StoredLocation destinationLocation,
        Instant requestedAt,
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

    private static String displayName(ServerPlayer player) {
        return PlayerTargets.displayName(player);
    }

    private static String quoteIfNeeded(String value) {
        if (value.indexOf(' ') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private record RequestLookup(RequestLookupStatus status, TeleportRequest request) {
        private static RequestLookup found(TeleportRequest request) {
            return new RequestLookup(RequestLookupStatus.FOUND, request);
        }

        private static RequestLookup expired() {
            return new RequestLookup(RequestLookupStatus.EXPIRED, null);
        }

        private static RequestLookup missing() {
            return new RequestLookup(RequestLookupStatus.MISSING, null);
        }

        private Optional<TeleportRequest> requestOptional() {
            return Optional.ofNullable(request);
        }
    }

    private enum RequestLookupStatus {
        FOUND,
        EXPIRED,
        MISSING
    }
}
