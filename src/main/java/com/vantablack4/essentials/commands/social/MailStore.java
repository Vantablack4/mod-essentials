package com.vantablack4.essentials.commands.social;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import com.vantablack4.essentials.VantablackEssentialsMod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

final class MailStore {
    static final int MAX_MESSAGE_LENGTH = 1000;
    private static final String NEXT_ID = "next-id";
    private static final String MAIL_PREFIX = "mail.";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT)
        .withZone(ZoneId.systemDefault());

    private final Path file;
    private final Properties properties;

    MailStore(Path configDirectory) {
        this.file = configDirectory.resolve("mail.properties");
        this.properties = loadProperties(file);
    }

    synchronized MailEntry send(MailRecipient recipient, MailSender sender, String message) {
        long id = nextId();
        String prefix = prefix(id);
        long now = System.currentTimeMillis();
        properties.setProperty(prefix + "recipient", recipient.key());
        properties.setProperty(prefix + "recipientName", recipient.displayName());
        properties.setProperty(prefix + "sender", sender.displayName());
        properties.setProperty(prefix + "senderKey", sender.key());
        properties.setProperty(prefix + "created", Long.toString(now));
        properties.setProperty(prefix + "read", "false");
        properties.setProperty(prefix + "message", message);
        properties.setProperty(NEXT_ID, Long.toString(id + 1));
        save();
        return new MailEntry(id, recipient.key(), recipient.displayName(), sender.key(), sender.displayName(), now, false, message);
    }

    synchronized List<MailEntry> listFor(ServerPlayer player) {
        Set<String> keys = recipientKeys(player);
        return allEntries().stream()
            .filter(entry -> keys.contains(entry.recipientKey()))
            .sorted(Comparator.comparingLong(MailEntry::createdEpochMillis).thenComparingLong(MailEntry::id))
            .toList();
    }

    synchronized List<MailEntry> readFor(ServerPlayer player) {
        List<MailEntry> entries = listFor(player);
        boolean changed = false;
        for (MailEntry entry : entries) {
            if (!entry.read()) {
                properties.setProperty(prefix(entry.id()) + "read", "true");
                changed = true;
            }
        }
        if (changed) {
            save();
        }
        return entries;
    }

    synchronized long unreadCount(ServerPlayer player) {
        return listFor(player).stream().filter(entry -> !entry.read()).count();
    }

    synchronized int clear(ServerPlayer player) {
        List<MailEntry> entries = listFor(player);
        for (MailEntry entry : entries) {
            remove(entry.id());
        }
        if (!entries.isEmpty()) {
            save();
        }
        return entries.size();
    }

    synchronized Optional<MailEntry> delete(ServerPlayer player, int oneBasedIndex) {
        List<MailEntry> entries = listFor(player);
        if (oneBasedIndex < 1 || oneBasedIndex > entries.size()) {
            return Optional.empty();
        }
        MailEntry removed = entries.get(oneBasedIndex - 1);
        remove(removed.id());
        save();
        return Optional.of(removed);
    }

    synchronized int clearAll() {
        List<Long> ids = allEntries().stream().map(MailEntry::id).toList();
        for (long id : ids) {
            remove(id);
        }
        if (!ids.isEmpty()) {
            save();
        }
        return ids.size();
    }

    MailRecipient recipientFor(MinecraftServer server, String rawTarget) {
        String target = SocialPlayerLookup.cleanInput(rawTarget);
        Optional<ServerPlayer> online = SocialPlayerLookup.findOnline(server, target);
        if (online.isPresent()) {
            ServerPlayer player = online.get();
            return new MailRecipient(uuidKey(player.getUUID()), SocialPlayerLookup.displayName(player));
        }

        Optional<UUID> uuid = SocialPlayerLookup.parseUuid(target);
        if (uuid.isPresent()) {
            Optional<NameAndId> profile = server.services().nameToIdCache().get(uuid.get());
            return new MailRecipient(uuidKey(uuid.get()), profile.map(NameAndId::name).orElse(uuid.get().toString()));
        }

        Optional<NameAndId> cachedProfile = server.services().nameToIdCache().get(target);
        if (cachedProfile.isPresent()) {
            NameAndId profile = cachedProfile.get();
            return new MailRecipient(uuidKey(profile.id()), profile.name());
        }

        return new MailRecipient(nameKey(target), target);
    }

    MailSender senderFor(ServerPlayer player) {
        return new MailSender(uuidKey(player.getUUID()), SocialPlayerLookup.displayName(player));
    }

    MailSender consoleSender() {
        return new MailSender("console", "Console");
    }

    static String formatDate(long epochMillis) {
        return DATE_FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    private List<MailEntry> allEntries() {
        return properties.stringPropertyNames().stream()
            .filter(key -> key.startsWith(MAIL_PREFIX) && key.endsWith(".recipient"))
            .map(key -> key.substring(MAIL_PREFIX.length(), key.length() - ".recipient".length()))
            .flatMap(id -> parseLong(id).stream())
            .map(this::readEntry)
            .flatMap(Optional::stream)
            .toList();
    }

    private Optional<MailEntry> readEntry(long id) {
        String prefix = prefix(id);
        String recipientKey = properties.getProperty(prefix + "recipient");
        String recipientName = properties.getProperty(prefix + "recipientName", recipientKey);
        String senderKey = properties.getProperty(prefix + "senderKey", "unknown");
        String sender = properties.getProperty(prefix + "sender", senderKey);
        String message = properties.getProperty(prefix + "message");
        Optional<Long> created = parseLong(properties.getProperty(prefix + "created", ""));
        if (recipientKey == null || recipientKey.isBlank() || message == null || created.isEmpty()) {
            return Optional.empty();
        }
        boolean read = Boolean.parseBoolean(properties.getProperty(prefix + "read", "false"));
        return Optional.of(new MailEntry(id, recipientKey, recipientName, senderKey, sender, created.get(), read, message));
    }

    private long nextId() {
        return parseLong(properties.getProperty(NEXT_ID, "1")).orElse(1L);
    }

    private void remove(long id) {
        String prefix = prefix(id);
        properties.remove(prefix + "recipient");
        properties.remove(prefix + "recipientName");
        properties.remove(prefix + "sender");
        properties.remove(prefix + "senderKey");
        properties.remove(prefix + "created");
        properties.remove(prefix + "read");
        properties.remove(prefix + "message");
    }

    private static Set<String> recipientKeys(ServerPlayer player) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(uuidKey(player.getUUID()));
        keys.add(nameKey(player.getScoreboardName()));
        return keys;
    }

    private static String uuidKey(UUID uuid) {
        return "uuid:" + uuid;
    }

    private static String nameKey(String name) {
        return "name:" + SocialPlayerLookup.cleanInput(name).toLowerCase(Locale.ROOT);
    }

    private static String prefix(long id) {
        return MAIL_PREFIX + id + ".";
    }

    private static Optional<Long> parseLong(String value) {
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Properties loadProperties(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to load Vantablack Essentials mail store: {}", file, exception);
        }
        return properties;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporaryFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "Vantablack Essentials mail");
            }
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save Vantablack Essentials mail: " + file, exception);
        }
    }

    record MailRecipient(String key, String displayName) {
    }

    record MailSender(String key, String displayName) {
    }

    record MailEntry(
        long id,
        String recipientKey,
        String recipientName,
        String senderKey,
        String senderName,
        long createdEpochMillis,
        boolean read,
        String message
    ) {
    }
}
