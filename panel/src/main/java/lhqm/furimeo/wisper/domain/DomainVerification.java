package lhqm.furimeo.wisper.domain;

/**
 * Whether the panel has seen this hostname actually pointing at the node holding its
 * service.
 *
 * <p>Matches {@code domain_verification_state_known} in V18, and
 * {@code domain_verified_consistent} makes {@link #VERIFIED} and a non-null
 * {@code verified_at} the same fact.
 *
 * <p>Verification is evidence, not a gate on routing: node-spec.md §3.6 puts every domain
 * row of a placed service into the node's spec whatever this says, so a customer can point
 * DNS and watch it start working. What it gates is {@code force_https} - redirecting a
 * visitor to a port whose handshake cannot succeed turns "not set up yet" into "broken" -
 * and it is what an operator reads when two people claim the same name.
 */
public enum DomainVerification {

    /**
     * Nobody has proved anything yet, and nothing has disproved it either.
     *
     * <p>Also where a check lands when there is nothing to compare against: the service is
     * on no node, or the node has no public address recorded. Neither is the customer's
     * fault and neither is a failure of theirs to fix.
     */
    PENDING,

    /**
     * The hostname resolved to the node holding the service, or the challenge TXT record
     * was found.
     *
     * <p>Sticky. Once proven, a hostname is not re-litigated by the recheck loop: DNS
     * moving during a migration would otherwise switch HTTPS off underneath a working site.
     * Releasing the name means removing the domain.
     */
    VERIFIED,

    /**
     * The lookup succeeded and the answer was wrong - the hostname does not exist, or it
     * points somewhere else.
     *
     * <p>Distinct from a resolver that could not be reached, which leaves the state exactly
     * as it was. "I could not ask" is not "the answer is no".
     */
    FAILED;

    public boolean isVerified() {
        return this == VERIFIED;
    }
}
