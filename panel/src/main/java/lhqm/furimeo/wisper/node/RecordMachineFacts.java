package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import lhqm.furimeo.wisper.proto.v1.DoctorReport;
import lhqm.furimeo.wisper.proto.v1.MachineFacts;
import lhqm.furimeo.wisper.sql.SqlTimestamp;

/**
 * Writes what the machine says it is: cores, memory, disk, kernel, Docker, {@code runsc},
 * the filesystem under {@code /var/lib/wisper}, and the whole preflight report.
 *
 * <p>Two callers, both node-driven: {@code EnrolNode} on the way in, and
 * {@code RecordHandshake} on every reconnect. It is refreshed every time rather than only
 * at enrolment because machines change - RAM gets added, a disk is replaced with a smaller
 * one, {@code runsc} is uninstalled by a distribution upgrade - and capacity that is a
 * month old is a placement that fails on arrival.
 *
 * <p>It writes only the columns a {@code MachineFacts} actually carries. The live numbers -
 * what is running right now, how much memory is in use - belong to {@link RecordHeartbeat}
 * and are not touched here, so a reconnect cannot blank them.
 *
 * <h2>Units</h2>
 *
 * <p>{@code cpu_millicores_capacity} is cores × 1000. The wire's {@code Capacity} uses
 * nano-CPUs to match Docker's {@code NanoCPUs}, which is a rate rather than a share; the
 * column is millicores because that is what schema.md fixed, and the conversion happens
 * here and in {@link RecordHeartbeat} and nowhere else.
 */
@Component
public class RecordMachineFacts {

    private static final Logger log = LoggerFactory.getLogger(RecordMachineFacts.class);

    private static final String SQL = """
            INSERT INTO node_status (node_id, cpu_cores, cpu_millicores_capacity,
                                     memory_bytes_capacity, disk_bytes_capacity, disk_bytes_used,
                                     docker_healthy, docker_version, runsc_available,
                                     kernel_version, os_description, clock_skew_millis,
                                     volume_filesystem, quota_enforceable,
                                     doctor_report, doctor_reported_at, updated_at, version)
            VALUES (:nodeId, :cpuCores, :cpuMillicores, :memoryBytes, :diskTotal, :diskUsed,
                    :dockerHealthy, :dockerVersion, :runscAvailable, :kernelVersion,
                    :osDescription, :clockSkewMillis, :volumeFilesystem, :quotaEnforceable,
                    :doctorReport, :doctorReportedAt, :at, 0)
            ON CONFLICT (node_id) DO UPDATE
               SET cpu_cores               = EXCLUDED.cpu_cores,
                   cpu_millicores_capacity = EXCLUDED.cpu_millicores_capacity,
                   memory_bytes_capacity   = EXCLUDED.memory_bytes_capacity,
                   disk_bytes_capacity     = EXCLUDED.disk_bytes_capacity,
                   disk_bytes_used         = EXCLUDED.disk_bytes_used,
                   docker_healthy          = EXCLUDED.docker_healthy,
                   docker_version          = EXCLUDED.docker_version,
                   runsc_available         = EXCLUDED.runsc_available,
                   kernel_version          = EXCLUDED.kernel_version,
                   os_description          = EXCLUDED.os_description,
                   clock_skew_millis       = EXCLUDED.clock_skew_millis,
                   volume_filesystem       = EXCLUDED.volume_filesystem,
                   quota_enforceable       = EXCLUDED.quota_enforceable,
                   doctor_report           = COALESCE(EXCLUDED.doctor_report,
                                                      node_status.doctor_report),
                   doctor_reported_at      = COALESCE(EXCLUDED.doctor_reported_at,
                                                      node_status.doctor_reported_at),
                   updated_at              = EXCLUDED.updated_at,
                   version                 = node_status.version + 1
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public RecordMachineFacts(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @param doctor the preflight report, or the default instance when the frame carried
     *               none. An absent report leaves the stored one alone rather than
     *               replacing it with nothing: an old report is worth more than no report,
     *               and it is stamped with when it was taken.
     */
    @Transactional
    public void accept(UUID nodeId, MachineFacts facts, DoctorReport doctor, Instant at) {
        String document = encode(nodeId, doctor);
        jdbc.sql(SQL)
                .param("nodeId", nodeId)
                .param("cpuCores", facts.getCpuCores())
                .param("cpuMillicores", (long) facts.getCpuCores() * 1000L)
                .param("memoryBytes", facts.getMemoryBytes())
                .param("diskTotal", facts.getDiskTotalBytes())
                .param("diskUsed", Math.max(0L, facts.getDiskTotalBytes()
                        - facts.getDiskFreeBytes()))
                // A version string is what Docker answers with when it is answering at
                // all, so an empty one at handshake time means the socket did not reply.
                .param("dockerHealthy", !facts.getDockerVersion().isBlank())
                .param("dockerVersion", facts.getDockerVersion())
                .param("runscAvailable", facts.getRunscAvailable())
                .param("kernelVersion", facts.getKernelVersion())
                .param("osDescription", facts.getOperatingSystem())
                .param("clockSkewMillis", facts.getClockOffsetMillis())
                .param("volumeFilesystem", facts.getStateFilesystem())
                // Only XFS enforces a project quota. Everywhere else a volume's size is a
                // number the panel displays and nothing applies (design §11.1).
                .param("quotaEnforceable", facts.getProjectQuotaSupported()
                        && "xfs".equalsIgnoreCase(facts.getStateFilesystem()))
                .param("doctorReport", document)
                .param("doctorReportedAt", document == null ? null : SqlTimestamp.at(at))
                .param("at", SqlTimestamp.at(at))
                .update();

        if (!facts.getRunscAvailable()) {
            log.warn("Node {} has no runsc; every workload on it will run under runc and the "
                    + "panel shows it as less isolated", nodeId);
        }
        if (!facts.getClockSynchronised()) {
            log.warn("Node {} reports an unsynchronised clock, {} ms out. ACME and TLS fail in "
                    + "ways that point everywhere except at the clock.",
                    nodeId, facts.getClockOffsetMillis());
        }
    }

    /**
     * Serialises the report, or returns null when there is nothing to store.
     *
     * <p>A serialisation failure does not fail the enrolment or drop the stream. The report
     * is diagnostic; refusing a node because its preflight document would not fit through
     * Jackson would be the panel making an operator's day worse over a rendering detail.
     */
    private String encode(UUID nodeId, DoctorReport doctor) {
        if (doctor == null || doctor.getChecksCount() == 0 && !doctor.hasMachine()) {
            return null;
        }
        try {
            return json.writeValueAsString(NodeDoctorReport.from(doctor));
        } catch (JacksonException failed) {
            log.error("Could not store the doctor report for node {}: {}", nodeId,
                    failed.getMessage());
            return null;
        }
    }
}
