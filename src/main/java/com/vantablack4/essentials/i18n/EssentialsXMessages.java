package com.vantablack4.essentials.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class EssentialsXMessages {
    public static final String DEFAULT_BASE_RESOURCE = "essentialsx/messages";

    private final ClassLoader classLoader;
    private final String baseResource;
    private final Properties rootMessages;
    private final ConcurrentMap<Locale, EssentialsMessageBundle> bundles = new ConcurrentHashMap<>();

    private EssentialsXMessages(ClassLoader classLoader, String baseResource, Properties rootMessages) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.baseResource = Objects.requireNonNull(baseResource, "baseResource");
        this.rootMessages = copyOf(rootMessages);
    }

    public static EssentialsXMessages loadDefault() {
        return load(DEFAULT_BASE_RESOURCE, EssentialsXMessages.class.getClassLoader());
    }

    public static EssentialsXMessages load(String baseResource, ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        String normalizedBase = normalizeBaseResource(baseResource);
        Properties rootMessages = loadRequiredProperties(classLoader, normalizedBase + ".properties");
        return new EssentialsXMessages(classLoader, normalizedBase, rootMessages);
    }

    public EssentialsMessageLookup lookup(Locale locale) {
        return new EssentialsMessageLookup(this, normalizeLocale(locale));
    }

    public EssentialsMessageLookup lookup(String localeName) {
        return lookup(parseLocale(localeName));
    }

    public Optional<String> raw(Locale locale, String key) {
        return bundle(locale).raw(key);
    }

    public Optional<String> message(Locale locale, String key, Object... arguments) {
        return bundle(locale).format(key, arguments);
    }

    public String messageOrDefault(Locale locale, String key, String fallback, Object... arguments) {
        Objects.requireNonNull(fallback, "fallback");
        String template = raw(locale, key).orElse(fallback);
        return EssentialsMessageFormatter.format(template, arguments);
    }

    public String messageOrKey(Locale locale, String key, Object... arguments) {
        Objects.requireNonNull(key, "key");
        return messageOrDefault(locale, key, key, arguments);
    }

    public boolean contains(Locale locale, String key) {
        return raw(locale, key).isPresent();
    }

    public static Locale parseLocale(String localeName) {
        if (localeName == null || localeName.isBlank()) {
            return Locale.ROOT;
        }
        return Locale.forLanguageTag(localeName.trim().replace('_', '-'));
    }

    private EssentialsMessageBundle bundle(Locale locale) {
        Locale normalizedLocale = normalizeLocale(locale);
        return bundles.computeIfAbsent(normalizedLocale, this::loadBundle);
    }

    private EssentialsMessageBundle loadBundle(Locale locale) {
        List<Properties> fallbackChain = new ArrayList<>();
        for (String resourceName : localeResourceNames(locale)) {
            loadOptionalProperties(classLoader, resourceName).ifPresent(fallbackChain::add);
        }
        fallbackChain.add(rootMessages);
        return new EssentialsMessageBundle(locale, fallbackChain);
    }

    private List<String> localeResourceNames(Locale locale) {
        Set<String> suffixes = new LinkedHashSet<>();
        String language = locale.getLanguage();
        String script = locale.getScript();
        String country = locale.getCountry();
        String variant = locale.getVariant();

        if (!language.isBlank()) {
            if (!script.isBlank()) {
                if (!country.isBlank() && !variant.isBlank()) {
                    suffixes.add(language + "_" + script + "_" + country + "_" + variant);
                }
                if (!country.isBlank()) {
                    suffixes.add(language + "_" + script + "_" + country);
                }
                suffixes.add(language + "_" + script);
            }
            if (!country.isBlank() && !variant.isBlank()) {
                suffixes.add(language + "_" + country + "_" + variant);
            }
            if (!country.isBlank()) {
                suffixes.add(language + "_" + country);
            }
            suffixes.add(language);
        }

        return suffixes.stream()
            .map(suffix -> baseResource + "_" + suffix + ".properties")
            .toList();
    }

    private static Locale normalizeLocale(Locale locale) {
        if (locale == null) {
            return Locale.ROOT;
        }
        return Locale.forLanguageTag(locale.toLanguageTag());
    }

    private static String normalizeBaseResource(String baseResource) {
        Objects.requireNonNull(baseResource, "baseResource");
        String normalized = baseResource.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("baseResource cannot be blank");
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith(".properties")) {
            normalized = normalized.substring(0, normalized.length() - ".properties".length());
        }
        return normalized;
    }

    private static Properties loadRequiredProperties(ClassLoader classLoader, String resourceName) {
        return loadOptionalProperties(classLoader, resourceName)
            .orElseThrow(() -> new IllegalStateException("Missing EssentialsX messages resource: " + resourceName));
    }

    private static Optional<Properties> loadOptionalProperties(ClassLoader classLoader, String resourceName) {
        try (InputStream stream = classLoader.getResourceAsStream(resourceName)) {
            if (stream == null) {
                return Optional.empty();
            }
            Properties properties = new Properties();
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return Optional.of(properties);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load EssentialsX messages resource: " + resourceName, exception);
        }
    }

    private static Properties copyOf(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }
}
