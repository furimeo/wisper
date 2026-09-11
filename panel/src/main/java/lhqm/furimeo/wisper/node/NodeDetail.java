package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.List;

/**
 * Everything one node's page shows.
 *
 * <p>{@link NodeSummary} is the top of the page and the row in the list; the rest is here
 * because it is only worth reading when somebody has opened a specific machine: the
 * preflight report, the enrolment history, and the exact command to run on a record nobody
 * has enrolled yet.
 *
 * @param summary          the same shape the fleet list uses, so both render from one
 *                         TypeScript declaration
 * @param doctor           the last preflight report, or null when the machine has never
 *                         sent one
 * @param tokens           every bootstrap token ever issued for this record, newest first.
 *                         The history is the point: an expired token, a token used from an
 *                         address nobody recognises and a token reissued after a failed
 *                         install are three different situations.
 * @param liveToken        the one token that could still be used, or null
 * @param installCommand   what an operator pastes into the machine's shell. Present only
 *                         while there is a live token, because it is useless without one.
 * @param installChecksum  the SHA-256 of {@code /install.sh} as this panel currently
 *                         renders it. It is the value the {@code sha256sum -c} line in the
 *                         command checks against, shown on the screen so an operator
 *                         verifies the script from somewhere other than the download
 *                         itself (design §7.1).
 * @param dialEndpoint     the gRPC endpoint this node was told to use
 * @param upgradeAvailable the newest published version, when it is newer than the one
 *                         running; null when there is nothing to offer
 * @param lastConnectedAt  when a stream last opened
 * @param lastDisconnected when one last closed
 * @param clockSkewMillis  how far the node's clock is from the panel's. Large skew breaks
 *                         ACME and TLS with symptoms that point everywhere else.
 */
public record NodeDetail(
        NodeSummary summary,
        NodeDoctorReport doctor,
        List<EnrolmentTokenView> tokens,
        EnrolmentTokenView liveToken,
        String installCommand,
        String installChecksum,
        String dialEndpoint,
        String upgradeAvailable,
        Instant lastConnectedAt,
        Instant lastDisconnected,
        Long clockSkewMillis,
        String volumeFilesystem,
        String kernelVersion,
        String osDescription,
        String dockerVersion) {
}
