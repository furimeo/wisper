package lhqm.furimeo.wisper.database;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.Timestamp;

import lhqm.furimeo.wisper.proto.v1.DatabaseStatus;

/**
 * Writes what a node observed about the databases on it.
 *
 * <p>Called by {@code grpc} for the {@code databases} half of a {@code StatusBatch}
 * (panel-ports.md §3), and it is the only path by which the node-owned columns on
 * {@code managed_database} and {@code database_engine} are ever written. It delegates the
 * two things that are not bookkeeping: {@link TrackDatabaseSize} does the writing and
 * {@link EnforceDatabaseQuota} decides what an over-quota measurement means.
 *
 * <h2>"Cannot see it" is not "does not exist"</h2>
 *
 * <p>A status with {@code exists == false} - the engine container is down, or a restore is
 * half finished - changes nothing about the row. No size is recorded, no state moves, and
 * nothing is deleted. The panel shows the database as unavailable and keeps everything it
 * knew, because the alternative is a customer's database disappearing from their screen
 * because a container was restarting (AGENTS.md §4.5).
 *
 * <h2>The engine's own columns</h2>
 *
 * <p>There is no engine-level status message on the wire: {@code DatabaseStatus} is per
 * grant. So the four node-owned columns on {@code database_engine} are derived from the
 * grants reported on it - running if any database answered, failed if every one of them
 * carried an error, and otherwise left exactly as they were. An engine nothing was said
 * about is not an engine that is down.
 */
@Component
public class RecordDatabaseStatus {

    private static final Logger log = LoggerFactory.getLogger(RecordDatabaseStatus.class);

    private final ManagedDatabaseRepository databases;
    private final TrackDatabaseSize sizes;
    private final EnforceDatabaseQuota quotas;

    public RecordDatabaseStatus(ManagedDatabaseRepository databases, TrackDatabaseSize sizes,
                                EnforceDatabaseQuota quotas) {
        this.databases = databases;
        this.sizes = sizes;
        this.quotas = quotas;
    }

    /**
     * @param statuses   every database the node observed in one reconcile pass
     * @param observedAt when the node's report reached the panel, used when a status
     *                   carries no measurement time of its own
     */
    @Transactional
    public void accept(UUID nodeId, List<DatabaseStatus> statuses, Instant observedAt) {
        if (nodeId == null || statuses == null || statuses.isEmpty()) {
            return;
        }
        Map<UUID, ManagedDatabase> held = new HashMap<>();
        for (ManagedDatabase database : databases.findAllOnNode(nodeId)) {
            held.put(database.id(), database);
        }

        Map<UUID, EngineReport> perEngine = new HashMap<>();
        List<Transition> transitions = new ArrayList<>();
        int ignored = 0;

        for (DatabaseStatus status : statuses) {
            UUID databaseId = parseId(status.getId());
            ManagedDatabase database = databaseId == null ? null : held.get(databaseId);
            if (database == null) {
                ignored++;
                continue;
            }
            EngineReport report = perEngine.computeIfAbsent(database.databaseEngineId(),
                    key -> new EngineReport());
            report.add(status);

            if (!status.getExists()) {
                // Nothing is written. The engine is down or the database is mid-restore,
                // and both are "not visible right now" rather than "gone".
                continue;
            }
            Instant measuredAt = status.hasMeasuredAt()
                    ? toInstant(status.getMeasuredAt()) : observedAt;
            if (!sizes.record(nodeId, databaseId, status.getSizeBytes(), measuredAt)) {
                ignored++;
                continue;
            }
            if (needsQuotaDecision(database, status.getSizeBytes())) {
                transitions.add(new Transition(databaseId, status.getOverQuota(),
                        status.getSizeBytes()));
            }
        }

        // After the measurements, so a suspension is decided against the size that was just
        // written rather than against the one from fifteen seconds ago.
        for (Transition transition : transitions) {
            quotas.apply(transition.databaseId(), transition.nodeSaysOver(),
                    transition.sizeBytes());
        }
        perEngine.forEach((engineId, report) ->
                sizes.recordEngine(nodeId, engineId, report.state(), observedAt,
                        report.totalBytes(), report.error()));

        if (ignored > 0) {
            // Not an error on its own: a report can arrive a moment after a customer
            // dropped the database. Logged because a node persistently naming ids it does
            // not hold is worth somebody noticing.
            log.info("Node {} reported {} database status(es) for grants it does not hold",
                    nodeId, ignored);
        }
    }

    /**
     * Whether this measurement could change the row's state.
     *
     * <p>Most reports change nothing: a database well under its limit that was already
     * {@code READY} needs no decision, and asking for one would be a select and a save
     * every fifteen seconds for every database on the platform.
     */
    private static boolean needsQuotaDecision(ManagedDatabase database, long sizeBytes) {
        boolean over = sizeBytes > database.quotaBytes();
        return over
                ? database.state() == ManagedDatabaseState.READY
                : database.state() == ManagedDatabaseState.SUSPENDED;
    }

    private static UUID parseId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    /** One database whose state the measurement may move. */
    private record Transition(UUID databaseId, boolean nodeSaysOver, long sizeBytes) {
    }

    /**
     * What every grant on one engine added up to, which is as close to an engine status as
     * the wire gets.
     */
    private static final class EngineReport {

        private int seen;
        private int visible;
        private int errors;
        private long totalBytes;
        private String firstError;

        void add(DatabaseStatus status) {
            seen++;
            if (status.getExists()) {
                visible++;
                totalBytes += Math.max(0L, status.getSizeBytes());
            }
            if (!status.getLastError().isEmpty()) {
                errors++;
                if (firstError == null) {
                    firstError = status.getLastError();
                }
            }
        }

        /** Matches {@code database_engine_reported_state_known}. */
        String state() {
            if (visible > 0) {
                return "RUNNING";
            }
            return errors == seen ? "FAILED" : "UNKNOWN";
        }

        long totalBytes() {
            return totalBytes;
        }

        String error() {
            return firstError;
        }
    }
}
