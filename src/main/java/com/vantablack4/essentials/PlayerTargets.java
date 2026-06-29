package com.vantablack4.essentials;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class PlayerTargets {
    private PlayerTargets() {
    }

    static Optional<ServerPlayer> find(MinecraftServer server, String rawTarget) {
        String target = stripWrappingQuotes(rawTarget).trim();
        if (target.isBlank()) {
            return Optional.empty();
        }

        ServerPlayer byProfileName = server.getPlayerList().getPlayer(target);
        if (byProfileName != null) {
            return Optional.of(byProfileName);
        }

        String folded = target.toLowerCase(Locale.ROOT);
        return server.getPlayerList().getPlayers().stream()
            .filter(player -> displayName(player).equalsIgnoreCase(target) || player.getScoreboardName().toLowerCase(Locale.ROOT).equals(folded))
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

    private static String stripWrappingQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"");
        }
        return value;
    }

    private static String quoteIfNeeded(String value) {
        if (value.indexOf(' ') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
