package lhqm.furimeo.wisper.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.Timestamp;

import lhqm.furimeo.wisper.proto.v1.RouteStatus;

/**
 * Writes what a node said about one hostname's certificate.
 *
 * <p>The only writer of the {@code certificate} table. The panel never issues, renews or
 * holds a certificate in v1: Caddy runs inside sasayaki, obtains it over ACME and keeps the
 * key on the node's disk (design §11.3). This class exists so the panel can show an expiry
 * date, warn before a renewal that is quietly failing runs out, and answer "why is this
 * hostname serving a warning" without asking a machine that may be offline.
 *
 * <h2>Reports repeat; rows must not churn</h2>
 *
 * <p>A node reports every route on every reconcile pass, so a naive writer would issue one
 * {@code UPDATE} per hostname per pass for the whole fleet, bumping {@code version} and
 * {@code updated_at} forever for state that never changed. Every branch below therefore
 * compares first and writes only on a difference.
 *
 * <h2>A failure does not take the live certificate away</h2>
 *
 * <p>An ACME failure against a hostname that already has a certificate means the renewal is
 * failing, not that visitors have stopped being served. The row stays live as
 * {@code RENEWING} with the error and the failure count on it - which is precisely the state
 * the "expiring and not renewing" warning is drawn from. Moving it to {@code FAILED} would
 * throw away the expiry that the warning needs.
 */
@Component
public class RecordCertificateStatus {

    /**
     * How stale {@code last_renewal_attempt_at} may get before a repeat report is written
     * anyway.
     *
     * <p>Long enough that a node reporting every fifteen seconds writes once per interval
     * rather than sixty times a minute, short enough that "the node is trying" does not look
     * abandoned on the screen.
     */
    private static final Duration ATTEMPT_RESOLUTION = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(RecordCertificateStatus.class);

    private final CertificateRepository certificates;

    public RecordCertificateStatus(CertificateRepository certificates) {
        this.certificates = certificates;
    }

    /**
     * Applies one {@code RouteStatus} to the hostname's certificate record.
     *
     * @param nodeId     the node that reported, already checked to be routing this hostname
     * @param domain     the row the report is about
     * @param observedAt when the node made the observation
     */
    @Transactional
    public void accept(UUID nodeId, Domain domain, RouteStatus status, Instant observedAt) {
        Instant notAfter = status.hasCertificateNotAfter()
                ? toInstant(status.getCertificateNotAfter()) : null;
        switch (status.getCertificate()) {
            case CERTIFICATE_STATE_VALID -> recordValid(nodeId, domain, notAfter, observedAt);
            case CERTIFICATE_STATE_ISSUING -> recordIssuing(nodeId, domain, observedAt);
            case CERTIFICATE_STATE_FAILED ->
                    recordFailure(nodeId, domain, status.getLastError(), observedAt);
            default -> {
                // NONE and UNSPECIFIED. A hostname with TLS switched off has no certificate
                // and never will, and a node that has stopped mentioning one has not revoked
                // it - it has stopped mentioning it. Concluding otherwise is "cannot see it"
                // read as "does not exist" (AGENTS.md §4.5).
            }
        }
    }

    /** The node is serving a certificate for this hostname. */
    private void recordValid(UUID nodeId, Domain domain, Instant notAfter, Instant observedAt) {
        if (notAfter == null || !notAfter.isAfter(observedAt)) {
            // certificate_issued_has_validity needs both bounds and
            // certificate_validity_ordered needs them in order. A report that cannot satisfy
            // them says nothing usable, and writing a guess would put a fabricated expiry on
            // the screen the customer plans their renewals from.
            log.info("Node {} reported a valid certificate for {} with no usable expiry; "
                    + "leaving the record as it was", nodeId, domain.hostname());
            return;
        }
        Certificate current = currentFor(domain, nodeId);
        if (current.state() == CertificateState.ISSUED
                && Objects.equals(current.notAfter(), notAfter)
                && Objects.equals(current.nodeId(), nodeId)
                && current.lastError() == null) {
            return;
        }
        // A different expiry means a different certificate, so its validity starts now. The
        // same expiry means the same certificate and the date it started is already right.
        Instant notBefore = Objects.equals(current.notAfter(), notAfter)
                && current.notBefore() != null && current.notBefore().isBefore(notAfter)
                ? current.notBefore() : observedAt;
        certificates.save(current.issued(nodeId, domain.hostname(), notBefore, notAfter,
                observedAt));
    }

    /** ACME is under way. */
    private void recordIssuing(UUID nodeId, Domain domain, Instant observedAt) {
        Certificate current = currentFor(domain, nodeId);
        Certificate next = current.isLive()
                ? current.renewing(nodeId, observedAt)
                : current.issuing(nodeId, observedAt);
        if (current.version() != null
                && current.state() == next.state()
                && Objects.equals(current.nodeId(), nodeId)
                && recentlyTouched(current.lastRenewalAttemptAt(), observedAt)) {
            return;
        }
        certificates.save(next);
    }

    /** ACME failed, and the message is the customer's next action. */
    private void recordFailure(UUID nodeId, Domain domain, String error, Instant observedAt) {
        Certificate current = currentFor(domain, nodeId);
        Certificate next = current.attemptFailed(nodeId, error, observedAt);
        if (current.version() != null
                && current.state() == next.state()
                && Objects.equals(current.nodeId(), nodeId)
                && Objects.equals(current.lastError(), next.lastError())) {
            // The same failure, still failing. The count means "how many times this has gone
            // wrong", not "how many times a node mentioned it", so a repeat is not a new one.
            return;
        }
        certificates.save(next);
    }

    /**
     * The row a report applies to: the live one, else the most recent, else a new one.
     *
     * <p>A {@code REVOKED} row is never revived - revocation is final, and a new attempt
     * after one is a new certificate's life. The returned record has a null {@code version}
     * exactly when it is new, which is how the callers above tell "nothing has ever been
     * recorded" from "recorded and unchanged".
     */
    private Certificate currentFor(Domain domain, UUID nodeId) {
        Optional<Certificate> live = certificates.findLiveFor(domain.id());
        if (live.isPresent()) {
            return live.get();
        }
        return certificates.findNewestFor(domain.id())
                .filter(existing -> !existing.state().isTerminal())
                .orElseGet(() -> Certificate.pendingFor(UUID.randomUUID(), domain.id(), nodeId));
    }

    private static boolean recentlyTouched(Instant attemptedAt, Instant observedAt) {
        return attemptedAt != null && attemptedAt.isAfter(observedAt.minus(ATTEMPT_RESOLUTION));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
