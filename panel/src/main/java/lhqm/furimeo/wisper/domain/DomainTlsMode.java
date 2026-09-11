package lhqm.furimeo.wisper.domain;

/**
 * How the node's embedded Caddy should serve a hostname.
 *
 * <p>Matches {@code domain_tls_mode_known} in V18. Three values here, two on the wire:
 * node-spec.md §3.6 collapses {@link #STATIC} onto on-demand issuance, because in v1 the
 * node owns every certificate and there is nowhere to upload one - {@code certificate}
 * holds metadata and, deliberately, no private key.
 */
public enum DomainTlsMode {

    /**
     * Caddy obtains a certificate the first time somebody asks for the hostname, and its
     * {@code ask} handler answers from the route table in the spec, in-process. That is
     * what keeps a site issuing and renewing while the panel is unreachable (design §5.4).
     */
    ON_DEMAND,

    /**
     * Reserved for an uploaded certificate.
     *
     * <p>Behaves exactly as {@link #ON_DEMAND} in v1 and is stored distinctly only because
     * a customer who chose it has said something about their intent. No screen offers it,
     * for the reason in the class comment.
     */
    STATIC,

    /**
     * Plain HTTP, no certificate attempted.
     *
     * <p>The setting for a hostname whose DNS still points somewhere else: the customer can
     * check the site over HTTP before cutting over, and ACME is not failing at them in the
     * meantime.
     */
    OFF;

    /** Whether the node will try to obtain a certificate for this hostname. */
    public boolean wantsCertificate() {
        return this != OFF;
    }
}
