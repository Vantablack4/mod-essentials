package com.vantablack4.essentials.commands.social;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class SocialPlayerLookup {
    private SocialPlayerLookup() {
    }

    static Optional<ServerPlayer> findOnline(MinecraftServer server, String rawTarget) {
        String target = cleanInput(rawTarget);
        if (target.isBlank()) {
            return Optional.empty();
        }

        Optional<UUID> uuid = parseUuid(target);
        if (uuid.isPresent()) {
            ServerPlayer byUuid = server.getPlayerList().getPlayer(uuid.get());
            if (byUuid != null) {
                return Optional.of(byUuid);
            }
        }

        ServerPlayer byProfileName = server.getPlayerList().getPlayer(target);
        if (byProfileName != null) {
            return Optional.of(byProfileName);
        }

        String folded = target.toLowerCase(Locale.ROOT);
        return server.getPlayerList().getPlayers().stream()
            .filter(player -> displayName(player).equalsIgnoreCase(target)
                || player.getScoreboardName().toLowerCase(Locale.ROOT).equals(folded))
            .findFirst();
    }

    static Set<String> suggestions(MinecraftServer server) {
        Set<String> suggestions = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            suggestions.add(player.getScoreboardName());
            suggestions.add(quoteIfNeeded(displayName(player)));
        }
        return suggestions;
    }

    static String displayName(ServerPlayer player) {
        return player.getDisplayName().getString();
    }

    static String cleanInput(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").trim();
        }
        return trimmed;
    }

    static String quoteIfNeeded(String value) {
        if (value.indexOf(' ') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
