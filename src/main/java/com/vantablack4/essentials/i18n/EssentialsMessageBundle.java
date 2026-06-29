package com.vantablack4.essentials.i18n;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

final class EssentialsMessageBundle {
    private final Locale locale;
    private final List<Properties> fallbackChain;

    EssentialsMessageBundle(Locale locale, List<Properties> fallbackChain) {
        this.locale = Objects.requireNonNull(locale, "locale");
        this.fallbackChain = List.copyOf(fallbackChain);
    }

    Locale locale() {
        return locale;
    }

    Optional<String> raw(String key) {
        Objects.requireNonNull(key, "key");
        for (Properties messages : fallbackChain) {
            String value = messages.getProperty(key);
            if (value != null) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    Optional<String> format(String key, Object... arguments) {
        return raw(key).map(message -> EssentialsMessageFormatter.format(message, arguments));
    }
}
