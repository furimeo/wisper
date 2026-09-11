package lhqm.furimeo.wisper.domain;

/**
 * The panel's view of one TLS certificate's life, as {@code certificate_state_known} in
 * V19 fixes it.
 *
 * <p>Every value here is written from a node's report and from nowhere else (schema.md
 * §2). The panel keeps the record so it can show an expiry, warn before a renewal that is
 * quietly failing runs out, and answer "why is this hostname serving a warning" without
 * asking a machine that may be offline.
 *
 * <p>This is deliberately not the same vocabulary as the wire's
 * {@code lhqm.furimeo.wisper.proto.v1.CertificateState}, which has four values describing
 * what the edge is doing right now. {@link RecordCertificateStatus} is the one place the
 * two are mapped.
 */
public enum CertificateState {

    /** A certificate is wanted and none has arrived. The first state of every row. */
    PENDING,

    /**
     * In force.
     *
     * <p>{@code certificate_issued_has_validity} requires {@code not_before} and
     * {@code not_after} on this state, so an expiry warning always has something to read.
     */
    ISSUED,

    /**
     * In force, and the node is currently trying to replace it.
     *
     * <p>Kept live rather than moved aside: the old certificate is still what visitors get
     * while ACME is being retried, and a renewal that has been failing for a fortnight is
     * exactly the thing the dashboard has to be able to show.
     */
    RENEWING,

    /** No certificate, and the last attempt produced an error worth showing. */
    FAILED,

    /**
     * Withdrawn.
     *
     * <p>Never reached from a status report - a node that stops mentioning a certificate
     * has not revoked it, it has simply not mentioned it, and concluding otherwise is
     * "cannot see it" read as "does not exist". Reserved for an explicit revocation.
     */
    REVOKED;

    /**
     * Whether this row is the one {@code certificate_live_per_domain_idx} allows exactly
     * one of.
     */
    public boolean isLive() {
        return this == ISSUED || this == RENEWING;
    }

    /** Whether the row is finished with and a new attempt would start a new life. */
    public boolean isTerminal() {
        return this == REVOKED;
    }
}
