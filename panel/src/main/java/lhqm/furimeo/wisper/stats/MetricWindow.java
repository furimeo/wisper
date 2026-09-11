package lhqm.furimeo.wisper.stats;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * The span a chart is asking for, parsed from a query string.
 *
 * <p>Two ways to ask, because the two are asked by different things. A person pressing
 * "last 24 hours" sends {@code ?window=24h}, which has to keep meaning "the last 24 hours"
 * when the page is left open. A chart being panned sends explicit {@code from} and
 * {@code to} instants, which must not drift.
 *
 * <p>Everything unparseable falls back rather than failing. A mistyped window on a chart
 * should draw the default range, not an error page: the customer came to look at a graph,
 * and refusing to draw one teaches them the feature is broken.
 *
 * @param from inclusive
 * @param to   exclusive
 */
public record MetricWindow(Instant from, Instant to) {

    /** The longest span a request may ask for, so one query cannot scan a decade. */
    public static final Duration MAX = Duration.ofDays(400);

    /**
     * Works out the window.
     *
     * @param window   a shorthand span: a number followed by {@code m}, {@code h} or
     *                 {@code d}. Null or unparseable means {@code fallback}
     * @param from     an ISO-8601 instant, or null
     * @param to       an ISO-8601 instant, or null; defaults to {@code now}
     * @param now      the panel's clock
     * @param fallback the span to use when nothing usable was asked for
     */
    public static MetricWindow of(String window, String from, String to, Instant now,
                                  Duration fallback) {
        Instant end = instant(to, now);
        Instant explicitStart = instant(from, null);
        if (explicitStart != null && explicitStart.isBefore(end)) {
            return new MetricWindow(clamp(explicitStart, end), end);
        }
        Duration span = span(window, fallback);
        return new MetricWindow(clamp(end.minus(span), end), end);
    }

    /** How wide this window is. */
    public Duration length() {
        return Duration.between(from, to);
    }

    private static Instant clamp(Instant start, Instant end) {
        Instant floor = end.minus(MAX);
        return start.isBefore(floor) ? floor : start;
    }

    /**
     * A shorthand span.
     *
     * <p>{@code 30m}, {@code 6h}, {@code 7d}. Deliberately not {@link Duration#parse},
     * whose {@code PT6H} nobody wants to put in a URL, and deliberately a closed set of
     * units so {@code 400000000d} cannot be spelled in a way that overflows before
     * {@link #clamp} sees it.
     */
    private static Duration span(String shorthand, Duration fallback) {
        if (shorthand == null || shorthand.length() < 2) {
            return fallback;
        }
        String text = shorthand.strip().toLowerCase(Locale.ROOT);
        char unit = text.charAt(text.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(text.substring(0, text.length() - 1));
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
        if (amount <= 0 || amount > 100_000) {
            return fallback;
        }
        return switch (unit) {
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> fallback;
        };
    }

    private static Instant instant(String candidate, Instant fallback) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(candidate.strip());
        } catch (DateTimeParseException notAnInstant) {
            return fallback;
        }
    }
}
