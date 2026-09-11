package lhqm.furimeo.wisper.backup;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Which snapshots a policy keeps. Pure arithmetic: snapshots in, two lists out, no
 * database and no clock.
 *
 * <p>The schema gives a customer two numbers, {@code retention_count} and
 * {@code retention_days}, because those are the two anybody can reason about. Keeping
 * thirty daily archives of a database that changes hourly is not what "thirty days" means
 * to the person who typed it, so those two numbers are turned into a
 * grandfather-father-son rule here, and the <em>same</em> rule is what
 * {@link ComposeRunBackup} puts in {@code RetentionRule} for the node. One derivation,
 * applied in both places, so the panel's idea of what exists and the node's idea of what
 * to delete cannot drift.
 *
 * <h2>The rule</h2>
 *
 * <ol>
 * <li>The newest {@link #keepLast} are kept, whatever their age. This is what stops a
 *     dormant project's only backup ageing out - and it is why there is no separate
 *     maximum-age pass that could delete it.</li>
 * <li>Walking newest to oldest, the first snapshot of each calendar day is kept until
 *     {@link #keepDaily} days have been claimed; then the first of each ISO week until
 *     {@link #keepWeekly}; then the first of each calendar month until
 *     {@link #keepMonthly}.</li>
 * <li>Everything else is expired.</li>
 * </ol>
 *
 * <p>A snapshot can satisfy several rules at once and is kept once. The tiers are counted
 * in <em>buckets claimed</em>, not snapshots kept, which is what makes "seven daily" mean
 * seven days rather than seven archives that might all be from Tuesday.
 *
 * <h2>Why the tiers cannot outlive {@code retention_days}</h2>
 *
 * <p>{@link #of} caps each tier by what the customer asked for: seven dailies but at most
 * {@code retention_days} of them, weeklies at most {@code retention_days / 7}, monthlies
 * at most {@code retention_days / 30}. A three-day policy therefore keeps three dailies
 * and no weeklies, and no amount of operator configuration can make it hold a snapshot for
 * a month.
 *
 * @param zone the policy's timezone. A day boundary is the customer's midnight: bucketing
 *             a 23:30 Ho Chi Minh snapshot by UTC would file it under the previous day and
 *             quietly keep two snapshots for one date and none for another
 */
public record RetentionPolicy(int keepLast, int keepDaily, int keepWeekly, int keepMonthly,
                              ZoneId zone) {

    /** Days in the month bucket, for turning {@code retention_days} into a monthly count. */
    private static final int DAYS_PER_MONTH = 30;

    private static final int DAYS_PER_WEEK = 7;

    public RetentionPolicy {
        keepLast = Math.max(1, keepLast);
        keepDaily = Math.max(0, keepDaily);
        keepWeekly = Math.max(0, keepWeekly);
        keepMonthly = Math.max(0, keepMonthly);
        zone = zone == null ? ZoneId.of("UTC") : zone;
    }

    /**
     * The rule for one policy, capped by the age the customer asked for.
     *
     * @param settings the platform's tier ceilings, which only ever lower the result
     */
    public static RetentionPolicy of(Backup policy, BackupSettings settings) {
        int days = Math.max(1, policy.retentionDays());
        return new RetentionPolicy(
                Math.max(1, policy.retentionCount()),
                Math.min(settings.keepDaily(), days),
                Math.min(settings.keepWeekly(), days / DAYS_PER_WEEK),
                Math.min(settings.keepMonthly(), days / DAYS_PER_MONTH),
                zoneOf(policy.timezone()));
    }

    /**
     * Splits snapshots into the ones kept and the ones retention lets go.
     *
     * <p>The input is sorted here rather than trusted, because the two callers read it from
     * two different queries and a decision that depends on row order is a decision that
     * changes when somebody adds an index.
     */
    public RetentionDecision select(List<RetainedSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return RetentionDecision.nothing();
        }
        List<RetainedSnapshot> newestFirst = new ArrayList<>(snapshots);
        newestFirst.sort(Comparator.comparing(RetainedSnapshot::takenAt).reversed()
                .thenComparing(RetainedSnapshot::id));

        Set<UUID> kept = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(keepLast, newestFirst.size()); i++) {
            kept.add(newestFirst.get(i).id());
        }
        claimBuckets(newestFirst, kept, keepDaily, this::dayOf);
        claimBuckets(newestFirst, kept, keepWeekly, this::weekOf);
        claimBuckets(newestFirst, kept, keepMonthly, this::monthOf);

        List<UUID> expired = new ArrayList<>();
        for (RetainedSnapshot snapshot : newestFirst) {
            if (!kept.contains(snapshot.id())) {
                expired.add(snapshot.id());
            }
        }
        return new RetentionDecision(orderedAsGiven(newestFirst, kept), expired);
    }

    /**
     * Keeps the newest snapshot of each distinct bucket until {@code limit} buckets have
     * been claimed.
     *
     * <p>A snapshot already kept by another rule still claims its bucket. Not doing so
     * would let the {@code keepLast} snapshots push the daily tier one day further back
     * each time, so "seven daily" would quietly become "seven daily plus however many the
     * last rule held".
     */
    private static void claimBuckets(List<RetainedSnapshot> newestFirst, Set<UUID> kept,
                                     int limit, BucketOf bucketOf) {
        if (limit <= 0) {
            return;
        }
        Set<Object> claimed = new HashSet<>();
        for (RetainedSnapshot snapshot : newestFirst) {
            if (claimed.size() >= limit) {
                return;
            }
            if (claimed.add(bucketOf.apply(snapshot))) {
                kept.add(snapshot.id());
            }
        }
    }

    private static List<UUID> orderedAsGiven(List<RetainedSnapshot> newestFirst, Set<UUID> kept) {
        List<UUID> ordered = new ArrayList<>(kept.size());
        for (RetainedSnapshot snapshot : newestFirst) {
            if (kept.contains(snapshot.id())) {
                ordered.add(snapshot.id());
            }
        }
        return ordered;
    }

    private LocalDate dayOf(RetainedSnapshot snapshot) {
        return snapshot.takenAt().atZone(zone).toLocalDate();
    }

    /**
     * The ISO week, as year-and-week rather than "the Monday of".
     *
     * <p>ISO weeks belong to a week-based year that does not always match the calendar one
     * - 1 January 2021 is week 53 of 2020 - and keying on the week number alone would make
     * two snapshots a year apart share a bucket.
     */
    private String weekOf(RetainedSnapshot snapshot) {
        LocalDate date = dayOf(snapshot);
        return date.get(IsoFields.WEEK_BASED_YEAR) + "-W"
                + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    private YearMonth monthOf(RetainedSnapshot snapshot) {
        return YearMonth.from(dayOf(snapshot));
    }

    private static ZoneId zoneOf(String timezone) {
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
        } catch (DateTimeException notAZone) {
            // The form validates this, so reaching here means a row was edited by hand.
            // Falling back is right: a bad zone must not stop retention from running, and
            // UTC is what the column defaults to anyway.
            return ZoneId.of("UTC");
        }
    }

    /** What bucket a snapshot falls in, for one tier. */
    @FunctionalInterface
    private interface BucketOf {
        Object apply(RetainedSnapshot snapshot);
    }
}
