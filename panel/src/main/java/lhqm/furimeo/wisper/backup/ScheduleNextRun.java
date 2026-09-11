package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.service.CronSchedule;

/**
 * Works out when a policy is next due.
 *
 * <p>Three callers need the same answer and would otherwise each write it: the form that
 * creates a policy, the form that edits one, and the sweep that advances the clock after
 * enqueuing a run. Two of the three getting it right is the version of this bug that is
 * hardest to see, because the schedule looks correct on the page and simply fires at the
 * wrong time.
 *
 * <p>The cron parser is {@code service.CronSchedule}, the same one customer cron tasks are
 * validated with. Deliberately not a second implementation: a platform where "0 3 * * *"
 * means two different things depending on which form you typed it into is a platform whose
 * schedules cannot be reasoned about. The parser also produces the message that names which
 * of the five fields is wrong, which is what a customer needs.
 *
 * <p>Unlike a customer's cron task, a backup schedule is evaluated <strong>by the
 * panel</strong>. A cron task must keep firing while the panel is unreachable, so the node
 * owns it; a backup needs the destination credentials, the retention view across every node
 * and a row to record the result in, none of which a node has.
 */
@Component
public class ScheduleNextRun {

    /**
     * The first firing of a new or edited schedule.
     *
     * @param schedule five-field cron, or null/blank for a policy that only runs on demand
     * @return when it should first run, or empty for a manual policy
     * @throws RequestRejected naming {@code schedule} or {@code timezone}
     */
    public Optional<Instant> firstRun(String schedule, String timezone, Instant from) {
        if (schedule == null || schedule.isBlank()) {
            return Optional.empty();
        }
        String zone = CronSchedule.requireZone(timezone);
        return CronSchedule.parse(schedule).nextRunAfter(from, ZoneId.of(zone));
    }

    /**
     * The firing after this one, for a policy that has just been picked up.
     *
     * <p>Empty for a manual policy, for a disabled one, and for an expression that parses
     * but matches nothing reachable - {@code 0 0 30 2 *}, the thirtieth of February. All
     * three mean the same thing to the sweep: stop looking at this row.
     */
    public Optional<Instant> after(Backup policy, Instant from) {
        if (!policy.isScheduled()) {
            return Optional.empty();
        }
        try {
            return firstRun(policy.schedule(), policy.timezone(), from);
        } catch (RequestRejected unparsable) {
            // Only reachable for a row edited outside the panel. The sweep must not stop
            // for it, and leaving next_run_at where it is would make this policy the head
            // of the due queue for ever.
            return Optional.empty();
        }
    }

    /** The normalised expression to store, or null for a manual policy. */
    public String normalise(String schedule) {
        if (schedule == null || schedule.isBlank()) {
            return null;
        }
        return CronSchedule.parse(schedule).expression();
    }
}
