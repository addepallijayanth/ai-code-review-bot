package com.example.reviewbot.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Number and time formatting used by the dashboard template. */
public class DashboardFormat {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm 'UTC'", Locale.ENGLISH);

    /** 1,284 / 12.9K / 4.2M. */
    public String compact(long value) {
        if (value < 10_000) {
            return String.format(Locale.ENGLISH, "%,d", value);
        }
        if (value < 1_000_000) {
            return trim(value / 1_000.0) + "K";
        }
        return trim(value / 1_000_000.0) + "M";
    }

    public String usd(BigDecimal value) {
        if (value == null) {
            return "$0";
        }
        if (value.compareTo(BigDecimal.ONE) < 0) {
            return "$" + value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        return "$" + String.format(Locale.ENGLISH, "%,.2f", value);
    }

    public String percent(double ratio) {
        return Math.round(ratio * 100) + "%";
    }

    /** 41s / 2m 5s; an em dash when there is no value yet. */
    public String latency(Number millis) {
        if (millis == null) {
            return "—";
        }
        Duration duration = Duration.ofMillis(Math.round(millis.doubleValue()));
        if (duration.toSeconds() < 60) {
            return duration.toSeconds() + "s";
        }
        return duration.toMinutes() + "m " + duration.toSecondsPart() + "s";
    }

    public String time(OffsetDateTime value) {
        return value == null ? "" : TIME.format(value.withOffsetSameInstant(ZoneOffset.UTC));
    }

    private static String trim(double value) {
        String text = String.format(Locale.ENGLISH, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
