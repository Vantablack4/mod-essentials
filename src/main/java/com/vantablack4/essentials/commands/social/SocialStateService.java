package com.vantablack4.essentials.commands.social;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.vantablack4.essentials.VantablackEssentialsMod;

final class SocialStateService {
    private static final String IGNORE_PREFIX = "ignore.";
    private static final String MESSAGES_DISABLED_PREFIX = "messagesDisabled.";
    private static final String REPLY_LAST_RECIPIENT_PREFIX = "replyLastRecipient.";
    private static final String SOCIAL_SPY_PREFIX = "socialSpy.";
    private static final String SHOUT_PREFIX = "shout.";
    private static final String NICK_PREFIX = "nick.";

    private final Path file;
    private final Properties properties;
    private final Map<UUID, UUID> replyTargets = new ConcurrentHashMap<>();
    private final Map<UUID, AfkStatus> afkStatuses = new ConcurrentHashMap<>();

    SocialStateService(Path configDirectory) {
        this.file = configDirectory.resolve("social-state.properties");
        this.properties = loadProperties(file);
    }

    synchronized Set<UUID> ignoredPlayers(UUID playerUuid) {
        return parseUuidSet(properties.getProperty(IGNORE_PREFIX + playerUuid, ""));
    }

    synchronized boolean isIgnored(UUID playerUuid, UUID targetUuid) {
        return ignoredPlayers(playerUuid).contains(targetUuid);
    }

    synchronized boolean toggleIgnored(UUID playerUuid, UUID targetUuid) {
        Set<UUID> ignored = ignoredPlayers(playerUuid);
        boolean added;
        if (ignored.contains(targetUuid)) {
            ignored.remove(targetUuid);
            added = false;
        } else {
            ignored.add(targetUuid);
            added = true;
        }
        properties.setProperty(IGNORE_PREFIX + playerUuid, serializeUuidSet(ignored));
        save();
        return added;
    }

    synchronized boolean messagesDisabled(UUID playerUuid) {
        return bool(MESSAGES_DISABLED_PREFIX + playerUuid, false);
    }

    synchronized boolean setMessagesDisabled(UUID playerUuid, Boolean disabled) {
        boolean next = disabled == null ? !messagesDisabled(playerUuid) : disabled;
        properties.setProperty(MESSAGES_DISABLED_PREFIX + playerUuid, Boolean.toString(next));
        save();
        return next;
    }

    synchronized boolean replyToLastRecipient(UUID playerUuid) {
        return bool(REPLY_LAST_RECIPIENT_PREFIX + playerUuid, true);
    }

    synchronized boolean setReplyToLastRecipient(UUID playerUuid, Boolean enabled) {
        boolean next = enabled == null ? !replyToLastRecipient(playerUuid) : enabled;
        properties.setProperty(REPLY_LAST_RECIPIENT_PREFIX + playerUuid, Boolean.toString(next));
        save();
        return next;
    }

    synchronized boolean socialSpy(UUID playerUuid) {
        return bool(SOCIAL_SPY_PREFIX + playerUuid, false);
    }

    synchronized boolean setSocialSpy(UUID playerUuid, Boolean enabled) {
        boolean next = enabled == null ? !socialSpy(playerUuid) : enabled;
        properties.setProperty(SOCIAL_SPY_PREFIX + playerUuid, Boolean.toString(next));
        save();
        return next;
    }

    synchronized boolean shout(UUID playerUuid) {
        return bool(SHOUT_PREFIX + playerUuid, false);
    }

    synchronized boolean setShout(UUID playerUuid, Boolean enabled) {
        boolean next = enabled == null ? !shout(playerUuid) : enabled;
        properties.setProperty(SHOUT_PREFIX + playerUuid, Boolean.toString(next));
        save();
        return next;
    }

    synchronized Optional<String> nickname(UUID playerUuid) {
        return Optional.ofNullable(properties.getProperty(NICK_PREFIX + playerUuid))
            .filter(value -> !value.isBlank());
    }

    synchronized void setNickname(UUID playerUuid, String nickname) {
        if (nickname == null || nickname.isBlank()) {
            properties.remove(NICK_PREFIX + playerUuid);
        } else {
            properties.setProperty(NICK_PREFIX + playerUuid, nickname);
        }
        save();
    }

    Optional<UUID> replyTarget(UUID playerUuid) {
        return Optional.ofNullable(replyTargets.get(playerUuid));
    }

    void senderMessaged(UUID senderUuid, UUID recipientUuid) {
        replyTargets.put(senderUuid, recipientUuid);
    }

    void recipientReceived(UUID recipientUuid, UUID senderUuid) {
        if (replyToLastRecipient(recipientUuid)) {
            replyTargets.putIfAbsent(recipientUuid, senderUuid);
        } else {
            replyTargets.put(recipientUuid, senderUuid);
        }
    }

    boolean isAfk(UUID playerUuid) {
        return afkStatuses.containsKey(playerUuid);
    }

    Optional<AfkStatus> afkStatus(UUID playerUuid) {
        return Optional.ofNullable(afkStatuses.get(playerUuid));
    }

    AfkStatus setAfk(UUID playerUuid, String message) {
        AfkStatus status = new AfkStatus(System.currentTimeMillis(), normalizeNullable(message));
        afkStatuses.put(playerUuid, status);
        return status;
    }

    Optional<AfkStatus> clearAfk(UUID playerUuid) {
        return Optional.ofNullable(afkStatuses.remove(playerUuid));
    }

    private boolean bool(String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1", "on", "enable", "enabled" -> true;
            case "false", "no", "0", "off", "disable", "disabled" -> false;
            default -> fallback;
        };
    }

    private static Set<UUID> parseUuidSet(String value) {
        Set<UUID> uuids = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return uuids;
        }
        Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(part -> !part.isBlank())
            .forEach(part -> SocialPlayerLookup.parseUuid(part).ifPresent(uuids::add));
        return uuids;
    }

    private static String serializeUuidSet(Set<UUID> uuids) {
        return uuids.stream().map(UUID::toString).sorted().reduce((left, right) -> left + "," + right).orElse("");
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Properties loadProperties(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to load Vantablack Essentials social state: {}", file, exception);
        }
        return properties;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporaryFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "Vantablack Essentials social state");
            }
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save Vantablack Essentials social state: " + file, exception);
        }
    }

    record AfkStatus(long sinceEpochMillis, String message) {
    }
}
