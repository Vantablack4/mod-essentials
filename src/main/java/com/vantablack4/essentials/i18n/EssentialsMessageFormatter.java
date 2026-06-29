package com.vantablack4.essentials.i18n;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EssentialsMessageFormatter {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    private EssentialsMessageFormatter() {
    }

    static String format(String template, Object... arguments) {
        Objects.requireNonNull(template, "template");
        Object[] safeArguments = arguments == null ? new Object[0] : arguments;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder formatted = new StringBuilder(template.length());
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            String replacement = index < safeArguments.length ? Objects.toString(safeArguments[index]) : matcher.group();
            matcher.appendReplacement(formatted, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(formatted);
        return formatted.toString().replace("''", "'");
    }
}
