package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.List;

import lhqm.furimeo.wisper.proto.v1.DoctorCheck;
import lhqm.furimeo.wisper.proto.v1.DoctorReport;
import lhqm.furimeo.wisper.proto.v1.MachineFacts;

/**
 * A {@code sasayaki doctor} report in the shape the panel stores and renders it in.
 *
 * <p>{@code node_status.doctor_report} is a {@code text} column holding a JSON document
 * (schema.md: PgJDBC sends a {@code String} as varchar and PostgreSQL will not cast that
 * to {@code jsonb}, so a {@code jsonb} column would force a converter into every package
 * that writes one). This record is that document: Jackson serialises it on the way in and
 * reads it back on the way out, and nothing ever queries inside it.
 *
 * <p>It is a translation of the protobuf message and not the protobuf message itself
 * because the generated types serialise as protobuf's JSON mapping - enums as their full
 * {@code DOCTOR_OUTCOME_PASS} spelling, timestamps as strings, unset fields as absent -
 * and a UI reading that would have to know protobuf's conventions. Translating once, here,
 * means the React side sees plain values.
 *
 * @param takenAt              when the report was produced on the machine
 * @param agentVersion         which sasayaki produced it; a report is only comparable
 *                             against the checks that existed when it was taken
 * @param requiredChecksPassed computed on the node so the installer and the panel cannot
 *                             disagree about what a pass is. False means an install must
 *                             not proceed.
 * @param checks               every preflight check, in the order the node ran them
 * @param machine              what the machine is
 */
public record NodeDoctorReport(
        Instant takenAt,
        String agentVersion,
        boolean requiredChecksPassed,
        List<Check> checks,
        Machine machine) {

    /** Translates the wire message. Absent sub-messages become empty, never null. */
    public static NodeDoctorReport from(DoctorReport report) {
        return new NodeDoctorReport(
                report.hasTakenAt()
                        ? Instant.ofEpochSecond(report.getTakenAt().getSeconds(),
                                report.getTakenAt().getNanos())
                        : null,
                report.getAgentVersion(),
                report.getRequiredChecksPassed(),
                report.getChecksList().stream().map(Check::from).toList(),
                Machine.from(report.getMachine()));
    }

    /**
     * One preflight check.
     *
     * @param id       stable - {@code docker.socket}, {@code kernel.cgroups2},
     *                 {@code clock.synchronised} - so two reports stay comparable after
     *                 the wording of {@code title} changes
     * @param severity {@code REQUIRED} or {@code ADVISORY}. Missing {@code runsc} is
     *                 advisory: the node runs on {@code runc} and the panel says so out
     *                 loud rather than refusing the machine (design §7.2).
     * @param outcome  {@code PASS}, {@code WARN} or {@code FAIL}
     * @param detail   what was found - the version that was too old, what held the port
     * @param remedy   what to type to fix it. A check that says no without saying what to
     *                 do next is a support ticket rather than a check.
     */
    public record Check(String id, String title, String severity, String outcome, String detail,
                        String remedy) {

        static Check from(DoctorCheck check) {
            return new Check(check.getId(), check.getTitle(),
                    strip(check.getSeverity().name(), "DOCTOR_SEVERITY_"),
                    strip(check.getOutcome().name(), "DOCTOR_OUTCOME_"),
                    check.getDetail(), check.getRemedy());
        }
    }

    /**
     * What the machine is, gathered at doctor time and again on every reconnect. RAM gets
     * added and disks get resized, and stale capacity is a placement that fails on
     * arrival.
     */
    public record Machine(
            String hostname,
            String operatingSystem,
            String kernelVersion,
            String architecture,
            int cpuCores,
            long memoryBytes,
            long diskTotalBytes,
            long diskFreeBytes,
            String stateFilesystem,
            boolean projectQuotaSupported,
            boolean cgroupsV2,
            String dockerVersion,
            String dockerApiVersion,
            boolean runscAvailable,
            String runscVersion,
            boolean port80Free,
            boolean port443Free,
            boolean clockSynchronised,
            long clockOffsetMillis,
            List<String> advertiseAddresses) {

        static Machine from(MachineFacts facts) {
            return new Machine(facts.getHostname(), facts.getOperatingSystem(),
                    facts.getKernelVersion(), facts.getArchitecture(), facts.getCpuCores(),
                    facts.getMemoryBytes(), facts.getDiskTotalBytes(), facts.getDiskFreeBytes(),
                    facts.getStateFilesystem(), facts.getProjectQuotaSupported(),
                    facts.getCgroupsV2(), facts.getDockerVersion(), facts.getDockerApiVersion(),
                    facts.getRunscAvailable(), facts.getRunscVersion(), facts.getPort80Free(),
                    facts.getPort443Free(), facts.getClockSynchronised(),
                    facts.getClockOffsetMillis(), List.copyOf(facts.getAdvertiseAddressesList()));
        }
    }

    private static String strip(String enumName, String prefix) {
        return enumName.startsWith(prefix) ? enumName.substring(prefix.length()) : enumName;
    }
}
