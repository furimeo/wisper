package lhqm.furimeo.wisper.node;

/**
 * An {@code Enroll} call the panel will not complete.
 *
 * <p>Unlike {@link NodeCredentialRejected}, this one says exactly what was wrong. The
 * caller already proved it holds a bootstrap token, an operator is watching the installer's
 * output, and the failure mode this project is avoiding is the one where "enrolment failed"
 * sends somebody looking at their firewall for an hour because their token expired while
 * they read the documentation (design §7.1, §7.2).
 *
 * <p>The reason also decides the gRPC status the {@code grpc} package answers with, so an
 * installer can branch on it without parsing English.
 */
public class EnrolmentRefused extends RuntimeException {

    private final Reason reason;

    public EnrolmentRefused(Reason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    public EnrolmentRefused(Reason reason) {
        this(reason, reason.defaultDetail());
    }

    public Reason reason() {
        return reason;
    }

    /** Why an enrolment was refused. */
    public enum Reason {

        /** No token matches. A typo, a truncated copy, or a token from another panel. */
        TOKEN_UNKNOWN("That bootstrap token is not one this panel issued."),

        /**
         * Past its fifteen minutes. Issue another from the node's page; that is one click
         * and the reason the TTL can afford to be short.
         */
        TOKEN_EXPIRED("That bootstrap token has expired. Issue a fresh one and run the "
                + "installer again."),

        /**
         * Already spent. Either the installer is being re-run - which is normal, and
         * wants a new token - or somebody else got there first, which is worth
         * investigating on the node's page where the address it was used from is
         * recorded.
         */
        TOKEN_SPENT("That bootstrap token has already been used. A token enrols exactly one "
                + "machine, once."),

        /** Withdrawn by an operator before anybody used it. */
        TOKEN_REVOKED("That bootstrap token was revoked."),

        /** The record it belongs to already has a machine on it. */
        NODE_ALREADY_ENROLLED("That node record has already been enrolled. Create a new node, "
                + "or delete this one first."),

        /**
         * The Ed25519 signature does not check out, so the sender does not hold the key it
         * is asking to be identified by.
         */
        PROOF_INVALID("The enrolment signature does not match the public key in the request."),

        /**
         * Another node is already using this machine fingerprint. Both records are
         * suspended and an operator is told, because the panel cannot tell which of the
         * two machines is the original and splitting a customer's workload across a
         * cloned pair is worse than stopping (design §7.3).
         */
        FINGERPRINT_TAKEN("Another node already reports this machine fingerprint. Both have "
                + "been suspended for an operator to look at."),

        /**
         * The agent speaks a protocol this panel does not. Refused here, before it holds a
         * credential, rather than at every {@code Connect} for the rest of its life
         * (design §7.5).
         */
        PROTOCOL_UNSUPPORTED("This panel does not speak that agent's protocol version."),

        /**
         * A required preflight check failed. The installer should never have got this far -
         * {@code sasayaki doctor} runs before anything is written - so reaching the panel
         * with a failing report means the installer was bypassed.
         */
        DOCTOR_FAILED("A required preflight check failed on that machine. Run "
                + "`sasayaki doctor` and fix what it reports."),

        /** A field the request cannot be understood without is missing or the wrong size. */
        MALFORMED("The enrolment request is missing something it cannot be read without.");

        private final String defaultDetail;

        Reason(String defaultDetail) {
            this.defaultDetail = defaultDetail;
        }

        public String defaultDetail() {
            return defaultDetail;
        }
    }
}
