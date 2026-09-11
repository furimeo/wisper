package lhqm.furimeo.wisper.domain;

import java.time.Duration;
import java.util.List;

/**
 * What one ownership check concluded, and whether it is worth asking again.
 *
 * <p>Returned rather than only written, because three callers need different halves of it:
 * the job needs to know when to come back, the controller needs a sentence for the flash
 * message, and the audit trail needs to know whether anything actually moved.
 *
 * @param hostname  the name that was checked, so the message can be assembled without the
 *                  caller reading the row again after the write
 * @param state     where the domain's {@code verification_state} now stands
 * @param detail    one sentence for the customer, naming what was found rather than only
 *                  that it was wrong. "It resolves to 203.0.113.9, not 198.51.100.4" is
 *                  actionable; "verification failed" is not.
 * @param changed   whether this check moved the state. Only a change is audited on the
 *                  automatic path - a recheck loop that wrote an entry every few minutes for
 *                  every unverified hostname would bury the trail it is part of.
 * @param recheckIn how long until it is worth looking again, or null when it is not: a
 *                  verified hostname is not re-litigated, and a domain that has been deleted
 *                  has nothing to check.
 */
public record VerificationResult(String hostname, DomainVerification state, String detail,
                                 boolean changed, Duration recheckIn) {

    public VerificationResult {
        if (state == null) {
            throw new IllegalArgumentException("A verification result needs a state");
        }
        hostname = hostname == null ? "" : hostname;
        detail = detail == null ? "" : detail;
    }

    /** The hostname resolves to the node holding its service, or the TXT record was found. */
    public static VerificationResult verified(String hostname, String detail, boolean changed) {
        return new VerificationResult(hostname, DomainVerification.VERIFIED, detail, changed,
                null);
    }

    /** The resolver answered and the answer was not this node. */
    public static VerificationResult failed(String hostname, String detail, boolean changed,
                                            Duration recheckIn) {
        return new VerificationResult(hostname, DomainVerification.FAILED, detail, changed,
                recheckIn);
    }

    /**
     * Nothing could be concluded: no node holds the service, the node has no public address,
     * or no resolver answered.
     *
     * <p>Distinct from {@link #failed} because none of those is the customer's DNS being
     * wrong, and telling them to fix a record that is already correct is worse than telling
     * them to wait.
     */
    public static VerificationResult pending(String hostname, String detail, boolean changed,
                                             Duration recheckIn) {
        return new VerificationResult(hostname, DomainVerification.PENDING, detail, changed,
                recheckIn);
    }

    /** The domain was removed while the check was queued. Nothing to write, nothing to retry. */
    public static VerificationResult gone() {
        return new VerificationResult("", DomainVerification.PENDING,
                "The hostname was removed before the check ran.", false, null);
    }

    public boolean isVerified() {
        return state == DomainVerification.VERIFIED;
    }

    /** Whether the job that ran this check should schedule another. */
    public boolean shouldRecheck() {
        return recheckIn != null;
    }

    /** The sentence a customer reads after pressing "Check now". */
    public String message() {
        return switch (state) {
            case VERIFIED -> hostname + " is verified. " + detail;
            case FAILED -> hostname + " is not pointing here yet. " + detail;
            case PENDING -> detail;
        };
    }

    /** How a set of addresses is written into a detail sentence. */
    static String describeAddresses(List<String> addresses) {
        if (addresses.isEmpty()) {
            return "nothing";
        }
        return addresses.size() <= 3
                ? String.join(", ", addresses)
                : String.join(", ", addresses.subList(0, 3)) + " and "
                        + (addresses.size() - 3) + " more";
    }
}
