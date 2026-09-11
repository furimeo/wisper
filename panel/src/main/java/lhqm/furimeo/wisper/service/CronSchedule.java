package lhqm.furimeo.wisper.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * A five-field cron expression, parsed so a customer finds out which field is wrong here
 * rather than from a node that stopped scheduling.
 *
 * <p>The schedule is evaluated <strong>on the node</strong>: a cron that has to fire while
 * the panel is unreachable cannot have the panel in its path (design §5.1). This class
 * therefore does not run anything. It exists for two jobs the panel does own - refusing an
 * expression that will not work, and computing {@code cron_task.next_run_at} so the screen
 * can say when the job is next due.
 *
 * <p>The {@code cron_task_schedule_shape} CHECK only counts five whitespace-separated
 * fields; everything below is the rest of the rule, in Java, where the message can name
 * the field.
 *
 * <pre>
 * minute  hour  day-of-month  month  day-of-week
 *   0-59  0-23      1-31       1-12    0-7 (0 and 7 are Sunday)
 * </pre>
 *
 * <p>Each field is {@code *}, a number, {@code a-b}, any of those with {@code /step}, or a
 * comma-separated list of them. Months accept {@code JAN}-{@code DEC} and days accept
 * {@code SUN}-{@code SAT}, because that is what people paste out of a crontab.
 *
 * <p>When both day-of-month and day-of-week are restricted, a day matching <em>either</em>
 * runs - the Vixie cron rule. It surprises people once, and disagreeing with every other
 * cron on earth would surprise them for ever.
 */
public final class CronSchedule {

    private static final List<String> MONTH_NAMES = List.of("JAN", "FEB", "MAR", "APR", "MAY",
            "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");

    private static final List<String> DAY_NAMES = List.of("SUN", "MON", "TUE", "WED", "THU",
            "FRI", "SAT");

    /** Four years of days: enough to answer 29 February, and to stop an unmatchable one. */
    private static final int SEARCH_DAYS = 366 * 4;

    private final String expression;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;
    private final boolean dayOfMonthRestricted;
    private final boolean dayOfWeekRestricted;

    private CronSchedule(String expression, BitSet minutes, BitSet hours, BitSet daysOfMonth,
                         BitSet months, BitSet daysOfWeek, boolean dayOfMonthRestricted,
                         boolean dayOfWeekRestricted) {
        this.expression = expression;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.dayOfMonthRestricted = dayOfMonthRestricted;
        this.dayOfWeekRestricted = dayOfWeekRestricted;
    }

    /**
     * Parses an expression.
     *
     * @throws RequestRejected naming {@code schedule}, with a message that says which of
     *                         the five fields is wrong and what it accepts
     */
    public static CronSchedule parse(String raw) {
        String expression = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (expression.isEmpty()) {
            throw new RequestRejected("schedule",
                    "Give a five-field schedule, for example \"0 3 * * *\" for 3am daily.");
        }
        String[] fields = expression.split(" ");
        if (fields.length != 5) {
            throw new RequestRejected("schedule",
                    "A schedule has five fields - minute, hour, day of month, month, day of "
                            + "week - and this has " + fields.length + ".");
        }
        BitSet minutes = field(fields[0], 0, 59, null, "minute");
        BitSet hours = field(fields[1], 0, 23, null, "hour");
        BitSet daysOfMonth = field(fields[2], 1, 31, null, "day of month");
        BitSet months = field(fields[3], 1, 12, MONTH_NAMES, "month");
        BitSet daysOfWeek = field(fields[4], 0, 7, DAY_NAMES, "day of week");
        // Sunday is both 0 and 7 in every cron anyone has used; fold them together so the
        // matcher only has to know about one.
        if (daysOfWeek.get(7)) {
            daysOfWeek.set(0);
            daysOfWeek.clear(7);
        }
        return new CronSchedule(expression, minutes, hours, daysOfMonth, months, daysOfWeek,
                !"*".equals(fields[2]), !"*".equals(fields[4]));
    }

    /**
     * Checks a timezone name and returns it.
     *
     * @throws RequestRejected naming {@code timezone}. A customer who asked for 3am means
     *                         their 3am, and a daily job that moves twice a year is a
     *                         support ticket nobody enjoys.
     */
    public static String requireZone(String raw) {
        String zone = raw == null || raw.isBlank() ? "UTC" : raw.strip();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException notAZone) {
            throw new RequestRejected("timezone",
                    "\"" + zone + "\" is not a timezone. Use an IANA name such as "
                            + "Asia/Ho_Chi_Minh or UTC.");
        }
        return zone;
    }

    /** The normalised expression, which is what gets stored and sent to the node. */
    public String expression() {
        return expression;
    }

    /**
     * The first moment after {@code from} that this schedule fires, in {@code zone}.
     *
     * <p>Empty for an expression that matches nothing reachable - {@code 0 0 30 2 *}, the
     * thirtieth of February - which parses fine and never runs. The screen shows "never"
     * for it rather than an empty cell that looks like a bug.
     */
    public Optional<Instant> nextRunAfter(Instant from, ZoneId zone) {
        ZonedDateTime cursor = from.atZone(zone).plusMinutes(1).withSecond(0).withNano(0);
        LocalDate date = cursor.toLocalDate();

        for (int day = 0; day < SEARCH_DAYS; day++, date = date.plusDays(1)) {
            if (!matchesDate(date)) {
                continue;
            }
            int firstHour = day == 0 ? cursor.getHour() : 0;
            for (int hour = firstHour; hour < 24; hour++) {
                if (!hours.get(hour)) {
                    continue;
                }
                int firstMinute = day == 0 && hour == cursor.getHour() ? cursor.getMinute() : 0;
                for (int minute = firstMinute; minute < 60; minute++) {
                    if (!minutes.get(minute)) {
                        continue;
                    }
                    // ZonedDateTime.of moves a time that falls in a spring-forward gap to
                    // the far side of it, which is what a customer expecting "2:30am"
                    // during the one night it does not exist should get: it runs.
                    ZonedDateTime candidate =
                            ZonedDateTime.of(date, LocalTime.of(hour, minute), zone);
                    if (!candidate.isBefore(cursor)) {
                        return Optional.of(candidate.toInstant());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean matchesDate(LocalDate date) {
        if (!months.get(date.getMonthValue())) {
            return false;
        }
        boolean dayOfMonthMatches = daysOfMonth.get(date.getDayOfMonth());
        // DayOfWeek is Monday=1..Sunday=7; cron is Sunday=0..Saturday=6.
        boolean dayOfWeekMatches = daysOfWeek.get(date.getDayOfWeek().getValue() % 7);
        if (dayOfMonthRestricted && dayOfWeekRestricted) {
            return dayOfMonthMatches || dayOfWeekMatches;
        }
        return dayOfMonthMatches && dayOfWeekMatches;
    }

    private static BitSet field(String text, int min, int max, List<String> names, String label) {
        BitSet set = new BitSet(max + 1);
        for (String part : text.split(",", -1)) {
            addRange(set, part.strip(), min, max, names, label);
        }
        if (set.isEmpty()) {
            throw new RequestRejected("schedule",
                    "The " + label + " field matches nothing.");
        }
        return set;
    }

    private static void addRange(BitSet set, String part, int min, int max, List<String> names,
                                 String label) {
        if (part.isEmpty()) {
            throw new RequestRejected("schedule",
                    "The " + label + " field has an empty entry in its list.");
        }
        int step = 1;
        String range = part;
        int slash = part.indexOf('/');
        if (slash >= 0) {
            range = part.substring(0, slash);
            step = number(part.substring(slash + 1), 1, max, null, label + " step");
            if (step < 1) {
                throw new RequestRejected("schedule",
                        "The " + label + " step has to be at least 1.");
            }
        }

        int from;
        int to;
        if (range.equals("*")) {
            from = min;
            to = max;
        } else {
            int dash = range.indexOf('-', range.startsWith("-") ? 1 : 0);
            if (dash > 0) {
                from = number(range.substring(0, dash), min, max, names, label);
                to = number(range.substring(dash + 1), min, max, names, label);
            } else {
                from = number(range, min, max, names, label);
                to = slash >= 0 ? max : from;
            }
        }
        if (from > to) {
            throw new RequestRejected("schedule",
                    "The " + label + " range " + range + " counts backwards.");
        }
        for (int value = from; value <= to; value += step) {
            set.set(value);
        }
    }

    private static int number(String text, int min, int max, List<String> names, String label) {
        String value = text.strip().toUpperCase(Locale.ROOT);
        if (names != null) {
            int index = names.indexOf(value);
            if (index >= 0) {
                // Months are one-based and named from January; days are zero-based from
                // Sunday, which is exactly the index.
                return names == MONTH_NAMES ? index + 1 : index;
            }
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new RequestRejected("schedule",
                        "The " + label + " field takes " + min + " to " + max + ", not " + parsed
                                + ".");
            }
            return parsed;
        } catch (NumberFormatException notANumber) {
            throw new RequestRejected("schedule",
                    "\"" + text + "\" is not something the " + label + " field understands.");
        }
    }
}
