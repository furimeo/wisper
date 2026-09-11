package lhqm.furimeo.wisper.placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.proto.v1.CronEntry;
import lhqm.furimeo.wisper.service.CronTask;
import lhqm.furimeo.wisper.service.CronTaskRepository;

/**
 * The customer's scheduled commands, as the node has to see them.
 *
 * <p>Cron is in the spec, unlike a backup, because it is the customer's schedule and it has
 * to keep firing while the panel is unreachable. A job that must run at three in the
 * morning inside a customer's container cannot have the panel in its path (design §5.1), so
 * the node evaluates the expression itself and {@code cron_task.next_run_at} is a display
 * value the node never reads.
 *
 * <p>Only enabled entries, and only for services that run a container: a static site has no
 * process to run a command inside. A disabled entry keeps its row, its history and its
 * schedule - switching one off must not lose what it did last week.
 */
@Component
public class BuildCronEntries {

    /**
     * The ids first, then the mapped rows.
     *
     * <p>Two statements rather than one so the {@code command text[]} column is read
     * through the repository that already maps it. One place in the panel decodes a
     * PostgreSQL array, and it is not this one.
     */
    private static final String ENABLED_IDS = """
            SELECT id FROM cron_task
             WHERE service_id IN (:serviceIds)
               AND enabled
            """;

    private final JdbcClient jdbc;
    private final CronTaskRepository cronTasks;

    public BuildCronEntries(JdbcClient jdbc, CronTaskRepository cronTasks) {
        this.jdbc = jdbc;
        this.cronTasks = cronTasks;
    }

    /** Every enabled entry belonging to an app this node holds, in a stable order. */
    @Transactional(readOnly = true)
    public List<CronEntry> from(List<PlacedService> placed) {
        List<UUID> appIds = placed.stream()
                .filter(PlacedService::isApp)
                .map(entry -> entry.service().id())
                .toList();
        if (appIds.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = jdbc.sql(ENABLED_IDS)
                .param("serviceIds", appIds)
                .query((row, number) -> row.getObject("id", UUID.class))
                .list();
        if (ids.isEmpty()) {
            return List.of();
        }

        List<CronTask> tasks = new ArrayList<>();
        cronTasks.findAllById(ids).forEach(tasks::add);
        tasks.sort(Comparator.comparing(CronTask::serviceId).thenComparing(CronTask::name));

        List<CronEntry> entries = new ArrayList<>(tasks.size());
        for (CronTask task : tasks) {
            entries.add(CronEntry.newBuilder()
                    .setId(task.id().toString())
                    // The workload the command runs inside, which is the service itself.
                    .setWorkloadId(task.serviceId().toString())
                    .setSchedule(task.schedule())
                    .setTimezone(task.timezone() == null ? "" : task.timezone())
                    .addAllCommand(List.of(task.command()))
                    .setTimeoutSeconds(task.timeoutSeconds())
                    // FORBID and REPLACE both arrive as "do not overlap": the wire has one
                    // boolean, and skipping is the safe reading of it until the contract
                    // grows a third state. See ConcurrencyPolicy.allowsOverlap.
                    .setAllowOverlap(task.concurrencyPolicy().allowsOverlap())
                    .build());
        }
        return List.copyOf(entries);
    }
}
