package lhqm.furimeo.wisper.stats;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one reading into {@code stat_sample}.
 *
 * <p>{@code ON CONFLICT DO NOTHING} is what makes {@code PushStats} idempotent. A node that
 * reconnects resends whatever it is not sure landed, and
 * {@code stat_sample_reading_key} - {@code (node_id, service_id, sampled_at)} with
 * {@code NULLS NOT DISTINCT} - turns the duplicate into a no-op instead of doubling a
 * chart. Doing it here rather than with a {@code CrudRepository} is the reason this class
 * exists: Spring Data JDBC's {@code save} has no upsert, and a "does it exist" query first
 * would be a race and an extra round trip on the panel's highest-volume write.
 *
 * <p>{@code created_at} and {@code version} are left to their column defaults, which is
 * safe precisely because this is hand-written SQL rather than an aggregate save - Spring
 * Data JDBC would have written both as NULL (panel-configuration.md).
 *
 * <p>A foreign key violation is swallowed. A node reporting a workload the panel deleted a
 * second ago is the normal shape of convergence, not an error, and a rejected batch would
 * cost every other sample in it.
 */
@Component
public class StoreSampleReading {

    private static final Logger log = LoggerFactory.getLogger(StoreSampleReading.class);

    private static final String INSERT = """
            INSERT INTO stat_sample (id, node_id, service_id, sampled_at, window_seconds,
                                     cpu_millicores, memory_bytes, memory_limit_bytes, disk_bytes,
                                     network_rx_bytes, network_tx_bytes, disk_read_bytes,
                                     disk_write_bytes, restart_count)
            VALUES (:id, :nodeId, :serviceId, :sampledAt, :windowSeconds,
                    :cpuMillicores, :memoryBytes, :memoryLimitBytes, :diskBytes,
                    :networkRxBytes, :networkTxBytes, :diskReadBytes,
                    :diskWriteBytes, :restartCount)
            ON CONFLICT DO NOTHING
            """;

    private final JdbcClient jdbc;

    public StoreSampleReading(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the reading, or does nothing if it is already there.
     *
     * @return true when a row was written, false for a duplicate or a subject that has
     *         since been deleted
     */
    @Transactional
    public boolean store(SampleReading reading) {
        try {
            return jdbc.sql(INSERT)
                    .param("id", UUID.randomUUID())
                    .param("nodeId", reading.nodeId())
                    .param("serviceId", reading.serviceId())
                    .param("sampledAt", timestamp(reading.sampledAt()))
                    .param("windowSeconds", reading.windowSeconds())
                    .param("cpuMillicores", reading.cpuMillicores())
                    .param("memoryBytes", reading.memoryBytes())
                    .param("memoryLimitBytes", reading.memoryLimitBytes())
                    .param("diskBytes", reading.diskBytes())
                    .param("networkRxBytes", reading.networkRxBytes())
                    .param("networkTxBytes", reading.networkTxBytes())
                    .param("diskReadBytes", reading.diskReadBytes())
                    .param("diskWriteBytes", reading.diskWriteBytes())
                    .param("restartCount", reading.restartCount())
                    .update() > 0;
        } catch (DataIntegrityViolationException gone) {
            // The service or the node was deleted between the node measuring and the panel
            // storing. Metrics are lossy by design; the workload is already on its way out.
            log.debug("Dropped a sample for node {} service {}: {}", reading.nodeId(),
                    reading.serviceId(), gone.getMostSpecificCause().getMessage());
            return false;
        }
    }

    /**
     * PgJDBC cannot bind an {@link Instant}: it refuses to infer a SQL type for one and
     * throws before the statement is sent. {@code timestamptz} takes an
     * {@link OffsetDateTime}, and UTC is the offset every instant in this schema is stored
     * at.
     */
    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
