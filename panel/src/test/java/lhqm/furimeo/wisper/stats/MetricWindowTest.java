package lhqm.furimeo.wisper.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * What a chart's query string means.
 *
 * <p>Nothing here throws. A mistyped window on a chart has to draw the default range,
 * because the customer came to look at a graph and an error page teaches them the feature
 * is broken rather than that they typed something odd.
 */
class MetricWindowTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:37:19Z");
    private static final Duration FALLBACK = Duration.ofMinutes(30);

    @Test
    void aShorthandSpanEndsAtNowAndStartsThatFarBack() {
        MetricWindow window = MetricWindow.of("6h", null, null, NOW, FALLBACK);

        assertThat(window.to()).isEqualTo(NOW);
        assertThat(window.from()).isEqualTo(NOW.minus(Duration.ofHours(6)));
    }

    @Test
    void minutesAndDaysAreUnderstoodToo() {
        assertThat(MetricWindow.of("90m", null, null, NOW, FALLBACK).length())
                .isEqualTo(Duration.ofMinutes(90));
        assertThat(MetricWindow.of("14d", null, null, NOW, FALLBACK).length())
                .isEqualTo(Duration.ofDays(14));
    }

    @Test
    void explicitInstantsWinOverTheShorthandBecauseAPannedChartMustNotDrift() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to = Instant.parse("2026-03-02T00:00:00Z");

        MetricWindow window = MetricWindow.of("6h", from.toString(), to.toString(), NOW, FALLBACK);

        assertThat(window.from()).isEqualTo(from);
        assertThat(window.to()).isEqualTo(to);
    }

    @Test
    void nonsenseFallsBackRatherThanFailing() {
        assertThat(MetricWindow.of("later", null, null, NOW, FALLBACK).length())
                .isEqualTo(FALLBACK);
        assertThat(MetricWindow.of("6y", null, null, NOW, FALLBACK).length()).isEqualTo(FALLBACK);
        assertThat(MetricWindow.of("-3h", null, null, NOW, FALLBACK).length()).isEqualTo(FALLBACK);
        assertThat(MetricWindow.of(null, "yesterday", "today", NOW, FALLBACK).length())
                .isEqualTo(FALLBACK);
    }

    @Test
    void aFromAfterItsToIsIgnoredInsteadOfProducingANegativeWindow() {
        MetricWindow window = MetricWindow.of(null, "2026-03-10T12:00:00Z",
                "2026-03-10T09:00:00Z", NOW, FALLBACK);

        assertThat(window.length()).isEqualTo(FALLBACK);
        assertThat(window.from()).isBefore(window.to());
    }

    @Test
    void aWindowLongerThanTheLongestRetentionIsClamped() {
        MetricWindow window = MetricWindow.of("9000d", null, null, NOW, FALLBACK);

        assertThat(window.length()).isEqualTo(MetricWindow.MAX);
    }
}
