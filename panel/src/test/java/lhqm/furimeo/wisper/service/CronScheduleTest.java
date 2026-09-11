package lhqm.furimeo.wisper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Parsing a five-field cron expression, and working out when it next fires.
 *
 * <p>The node does the scheduling; this is what refuses an expression before it is stored
 * and what puts a due time on the screen. Both jobs are wrong in the same way if the
 * parser is generous, so the refusals are tested as carefully as the matches.
 */
class CronScheduleTest {

    private static final ZoneId SAIGON = ZoneId.of("Asia/Ho_Chi_Minh");

    private static Instant at(String isoLocal, ZoneId zone) {
        return ZonedDateTime.of(LocalDateTime.parse(isoLocal), zone).toInstant();
    }

    @Test
    void aDailyJobFiresAtTheNextOccurrenceInItsOwnZone() {
        CronSchedule schedule = CronSchedule.parse("0 3 * * *");

        Optional<Instant> next = schedule.nextRunAfter(at("2026-03-01T04:00:00", SAIGON), SAIGON);

        assertThat(next).contains(at("2026-03-02T03:00:00", SAIGON));
    }

    @Test
    void theSameExpressionInAnotherZoneIsAnotherInstant() {
        CronSchedule schedule = CronSchedule.parse("0 3 * * *");
        Instant from = at("2026-03-01T00:00:00", ZoneId.of("UTC"));

        assertThat(schedule.nextRunAfter(from, ZoneId.of("UTC")))
                .isNotEqualTo(schedule.nextRunAfter(from, SAIGON));
    }

    @Test
    void aStepFiresEveryNthMinute() {
        CronSchedule schedule = CronSchedule.parse("*/15 * * * *");

        assertThat(schedule.nextRunAfter(at("2026-03-01T10:07:30", SAIGON), SAIGON))
                .contains(at("2026-03-01T10:15:00", SAIGON));
        assertThat(schedule.nextRunAfter(at("2026-03-01T10:46:00", SAIGON), SAIGON))
                .contains(at("2026-03-01T11:00:00", SAIGON));
    }

    @Test
    void aListAndARangeBothMatch() {
        CronSchedule schedule = CronSchedule.parse("0 9,17 * * 1-5");

        // Friday 17:00 -> Monday 09:00, because Saturday and Sunday are not in 1-5.
        assertThat(schedule.nextRunAfter(at("2026-03-06T17:00:00", SAIGON), SAIGON))
                .contains(at("2026-03-09T09:00:00", SAIGON));
    }

    @Test
    void namesAreAcceptedForMonthsAndDays() {
        assertThat(CronSchedule.parse("0 0 1 JAN *").expression()).isEqualTo("0 0 1 JAN *");
        assertThat(CronSchedule.parse("0 0 * * MON")
                .nextRunAfter(at("2026-03-04T12:00:00", SAIGON), SAIGON))
                .contains(at("2026-03-09T00:00:00", SAIGON));
    }

    @Test
    void sundayIsBothZeroAndSeven() {
        Instant from = at("2026-03-04T12:00:00", SAIGON);

        assertThat(CronSchedule.parse("0 0 * * 0").nextRunAfter(from, SAIGON))
                .isEqualTo(CronSchedule.parse("0 0 * * 7").nextRunAfter(from, SAIGON));
    }

    @Test
    void restrictingBothDayFieldsMatchesEitherOfThem() {
        // Vixie cron: day-of-month 1 OR Monday, not both.
        CronSchedule schedule = CronSchedule.parse("0 0 1 * 1");

        assertThat(schedule.nextRunAfter(at("2026-03-05T12:00:00", SAIGON), SAIGON))
                .contains(at("2026-03-09T00:00:00", SAIGON));
        assertThat(schedule.nextRunAfter(at("2026-03-30T12:00:00", SAIGON), SAIGON))
                .contains(at("2026-04-01T00:00:00", SAIGON));
    }

    @Test
    void aDateThatNeverArrivesHasNoNextRunRatherThanLoopingForEver() {
        CronSchedule schedule = CronSchedule.parse("0 0 30 2 *");

        assertThat(schedule.nextRunAfter(Instant.now(), SAIGON)).isEmpty();
    }

    @Test
    void whitespaceIsCollapsedSoTheStoredExpressionIsCanonical() {
        assertThat(CronSchedule.parse("  0   3  *  * *  ").expression()).isEqualTo("0 3 * * *");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0 3 * *", "0 3 * * * *", "60 3 * * *", "0 24 * * *",
            "0 3 0 * *", "0 3 * 13 *", "0 3 * * 8", "x 3 * * *", "5-1 3 * * *", "0 3 * * MONDAY"})
    void anExpressionTheNodeCouldNotSchedulIsRefusedWithTheFieldNamed(String expression) {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> CronSchedule.parse(expression))
                .matches(rejected -> "schedule".equals(rejected.field()));
    }

    @Test
    void aTimezoneIsCheckedAndDefaultsToUtc() {
        assertThat(CronSchedule.requireZone(null)).isEqualTo("UTC");
        assertThat(CronSchedule.requireZone("  ")).isEqualTo("UTC");
        assertThat(CronSchedule.requireZone("Asia/Ho_Chi_Minh")).isEqualTo("Asia/Ho_Chi_Minh");

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> CronSchedule.requireZone("Middle/Earth"))
                .matches(rejected -> "timezone".equals(rejected.field()));
    }

    @Test
    void theNextRunIsStrictlyAfterTheMomentAsked() {
        CronSchedule everyMinute = CronSchedule.parse("* * * * *");
        Instant from = at("2026-03-01T10:00:00", SAIGON);

        assertThat(everyMinute.nextRunAfter(from, SAIGON))
                .contains(at("2026-03-01T10:01:00", SAIGON));
    }
}
