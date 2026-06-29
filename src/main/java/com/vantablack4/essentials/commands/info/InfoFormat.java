package com.vantablack4.essentials.commands.info;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class InfoFormat {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT)
        .withZone(ZoneId.systemDefault());

    private InfoFormat() {
    }

    static String megabytes(long bytes) {
        return Long.toString(bytes / 1024L / 1024L) + " MB";
    }

    static String ticksAsDuration(long ticks) {
        return duration(Duration.ofMillis(Math.max(0L, ticks) * 50L));
    }

    static String duration(Duration duration) {
        long seconds = Math.max(0L, duration.toSeconds());
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;

        List<String> parts = new ArrayList<>();
        addPart(parts, days, "d");
        addPart(parts, hours, "h");
        addPart(parts, minutes, "m");
        if (parts.isEmpty() || parts.size() < 2) {
            addPart(parts, seconds, "s");
        }
        return parts.isEmpty() ? "0s" : String.join(" ", parts);
    }

    static String since(Instant instant) {
        return at(instant) + " (" + duration(Duration.between(instant, Instant.now())) + " ago)";
    }

    static String at(Instant instant) {
        return DATE_TIME.format(instant);
    }

    static String tickMillis(long nanos) {
        return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0D);
    }

    static String tps(long nanos) {
        if (nanos <= 0L) {
            return "20.00";
        }
        double tps = Math.min(20.0D, 1_000_000_000.0D / nanos);
        return String.format(Locale.ROOT, "%.2f", tps);
    }

    static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static void addPart(List<String> parts, long value, String unit) {
        if (value > 0L) {
            parts.add(value + unit);
        }
    }
}
