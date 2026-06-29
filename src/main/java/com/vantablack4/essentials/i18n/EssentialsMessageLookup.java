package com.vantablack4.essentials.i18n;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class EssentialsMessageLookup {
    private final EssentialsXMessages messages;
    private final Locale locale;

    EssentialsMessageLookup(EssentialsXMessages messages, Locale locale) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.locale = Objects.requireNonNull(locale, "locale");
    }

    public Locale locale() {
        return locale;
    }

    public Optional<String> raw(String key) {
        return messages.raw(locale, key);
    }

    public Optional<String> message(String key, Object... arguments) {
        return messages.message(locale, key, arguments);
    }

    public String messageOrDefault(String key, String fallback, Object... arguments) {
        return messages.messageOrDefault(locale, key, fallback, arguments);
    }

    public String messageOrKey(String key, Object... arguments) {
        return messages.messageOrKey(locale, key, arguments);
    }

    public boolean contains(String key) {
        return messages.contains(locale, key);
    }
}
