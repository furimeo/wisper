package lhqm.furimeo.wisper.domain;

/**
 * What a hostname is to its service.
 *
 * <p>Matches {@code domain_kind_known} in V18, and {@code domain_wildcard_shape} ties
 * {@link #WILDCARD} to a {@code *.} prefix: the kind and the shape of the string are the
 * same fact, and the database refuses to let them disagree.
 */
public enum DomainKind {

    /**
     * The hostname the panel shows as "your site is at".
     *
     * <p>At most one per service, enforced by {@code domain_primary_per_service_idx}. The
     * first hostname a customer adds becomes this one, so a service is never in the state
     * of having addresses and no address to show.
     */
    PRIMARY,

    /** Any further hostname pointing at the same service. */
    ALIAS,

    /**
     * {@code *.example.com}.
     *
     * <p>Present because the column allows it and a row written by a future version has to
     * map. <strong>The panel refuses to create one in v1</strong>: a wildcard certificate
     * can only be obtained with an ACME DNS-01 challenge, the platform manages no DNS
     * (design §12), and the node's on-demand TLS answers HTTP-01 per hostname. Accepting a
     * wildcard would mean a domain that routes and can never be served over HTTPS.
     *
     * @see Hostname#require(String)
     */
    WILDCARD;

    /** Whether the panel will let a customer create a hostname of this kind. */
    public boolean isCreatable() {
        return this != WILDCARD;
    }
}
