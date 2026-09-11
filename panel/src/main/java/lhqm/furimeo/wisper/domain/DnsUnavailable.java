package lhqm.furimeo.wisper.domain;

/**
 * A resolver could not be asked, or refused to answer.
 *
 * <p>The distinction this class exists for: <strong>"I could not ask" is not "the answer is
 * no"</strong>. A timeout, a SERVFAIL or a panel with no network reaches here; a hostname
 * that does not exist, or that resolves somewhere else, does not - those are answers, and
 * {@link VerifyDomainOwnership} records them as a failed check.
 *
 * <p>Getting this wrong would mark every domain on the platform as failing the moment the
 * panel's own DNS hiccuped, and {@code force_https} follows verification, so it would also
 * switch HTTPS redirects off across every site at once.
 */
public class DnsUnavailable extends RuntimeException {

    private final String name;

    public DnsUnavailable(String name, String message, Throwable cause) {
        super(message, cause);
        this.name = name;
    }

    /** The name that was being looked up. */
    public String name() {
        return name;
    }
}
