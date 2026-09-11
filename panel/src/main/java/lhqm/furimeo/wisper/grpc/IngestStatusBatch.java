package lhqm.furimeo.wisper.grpc;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.database.RecordDatabaseStatus;
import lhqm.furimeo.wisper.domain.RecordRouteStatus;
import lhqm.furimeo.wisper.node.RecordStatusReport;
import lhqm.furimeo.wisper.placement.RecordWorkloadStatus;
import lhqm.furimeo.wisper.proto.v1.StatusBatch;
import lhqm.furimeo.wisper.service.RecordCronStatus;

/**
 * Splits one reconcile report across the five packages that own the tables it touches.
 *
 * <p>A {@code StatusBatch} is one message because the node produced it in one pass, and it
 * is five writes because a workload, a route, a database and a cron entry live in four
 * different aggregates with four different owners. {@code grpc} does the splitting and
 * writes none of it (panel-ports.md §3).
 *
 * <h2>{@code partial} travels with the report</h2>
 *
 * <p>It is passed to {@code placement} rather than swallowed here, because it changes what
 * a missing workload means. In a complete batch, absent means gone. In a partial one,
 * Docker did not answer and absent means nothing at all - concluding otherwise is how a
 * panel decides a customer's container has disappeared because a socket timed out
 * (AGENTS.md §4.5).
 *
 * <p>The other three take no {@code partial} flag: routes come from the embedded Caddy,
 * databases from an engine container's own client, and cron from the node's scheduler,
 * none of which stop answering because the Docker socket did.
 *
 * <h2>The order is not arbitrary</h2>
 *
 * <p>The node header goes first, and it is the one write that can conclude the node must
 * be suspended - {@code applied_generation} above anything this panel published means two
 * panels are driving one machine. When it does, the rest is skipped: writing a status
 * report from a node the panel has just decided it is not the author of would be recording
 * the other panel's work as this one's.
 */
@Component
public class IngestStatusBatch {

    private final RecordStatusReport statusReport;
    private final RecordWorkloadStatus workloadStatus;
    private final RecordRouteStatus routeStatus;
    private final RecordDatabaseStatus databaseStatus;
    private final RecordCronStatus cronStatus;

    public IngestStatusBatch(RecordStatusReport statusReport,
                             RecordWorkloadStatus workloadStatus, RecordRouteStatus routeStatus,
                             RecordDatabaseStatus databaseStatus, RecordCronStatus cronStatus) {
        this.statusReport = statusReport;
        this.workloadStatus = workloadStatus;
        this.routeStatus = routeStatus;
        this.databaseStatus = databaseStatus;
        this.cronStatus = cronStatus;
    }

    /**
     * @return false when the node was suspended by this batch and must not be published to
     *         again, which the caller answers with a status rather than an {@code Ack}
     */
    public boolean accept(UUID nodeId, StatusBatch batch, Instant receivedAt) {
        if (statusReport.accept(nodeId, batch, receivedAt)) {
            return false;
        }
        Instant observedAt = batch.hasObservedAt()
                ? Instant.ofEpochSecond(batch.getObservedAt().getSeconds(),
                        batch.getObservedAt().getNanos())
                : receivedAt;

        workloadStatus.accept(nodeId, batch.getWorkloadsList(), observedAt, batch.getPartial());
        routeStatus.accept(nodeId, batch.getRoutesList(), observedAt);
        databaseStatus.accept(nodeId, batch.getDatabasesList(), observedAt);
        cronStatus.accept(nodeId, batch.getCronList(), observedAt);
        return true;
    }
}
