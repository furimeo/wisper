package lhqm.furimeo.wisper.domain;

import java.net.IDN;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Turns what somebody typed into the exact byte string the {@code domain} table, the
 * node's route table and Caddy's on-demand handler all have to agree on.
 *
 * <p>There is one representation of a hostname in this platform: lower-case, IDNA
 * A-label, no trailing dot. It has to be exactly one, because {@code domain_hostname_key}
 * is a plain unique index and the {@code ask} handler compares the SNI byte for byte.
 * {@code Example.COM.} and {@code example.com} reaching the table as two rows would let
 * two customers own one name, which is the hijack this whole package exists to prevent.
 *
 * <p>{@code domain_hostname_lowercase} and {@code domain_hostname_shape} enforce the
 * result in the database. Everything here is checked first so the customer gets a sentence
 * naming the mistake instead of a constraint violation.
 */
public final class Hostname {

    /** The longest a fully-qualified name may be, once the trailing dot is dropped. */
    public static final int MAX_LENGTH = 253;

    /** The longest one label between dots may be. */
    public static final int MAX_LABEL_LENGTH = 63;

    /** The shape {@code domain_hostname_shape} accepts, minus the wildcard alternative. */
    private static final String SHAPE = "[a-z0-9]([a-z0-9.-]*[a-z0-9])?";

    /**
     * Suffixes no public certificate authority will ever issue for.
     *
     * <p>Accepting one would produce a hostname that routes and then fails every ACME
     * attempt forever, which reads to the customer as the platform being broken. RFC 6761
     * and RFC 8375 special-use names, plus the labels ICANN reserved against future
     * delegation that private networks use anyway.
     */
    private static final Set<String> UNISSUABLE_SUFFIXES = Set.of(
            "localhost", "localdomain", "local", "home", "home.arpa", "lan", "intranet",
            "internal", "private", "corp", "test", "invalid", "example", "onion");

    private Hostname() {
    }

    /**
     * The canonical form of a hostname a customer typed, or a refusal that says why.
     *
     * @param raw whatever arrived in the form: mixed case, a trailing dot, Unicode, spaces
     *            around it
     * @return the lower-case A-label form, ready to be stored and compared
     * @throws RequestRejected against the {@code hostname} field, with one sentence a
     *                         customer can act on
     */
    public static String require(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw reject("Enter the hostname you want to point at this service, "
                    + "for example app.example.com.");
        }
        if (isWildcard(trimmed)) {
            throw reject("Wildcard hostnames are not supported. A wildcard certificate can only "
                    + "be issued through a DNS challenge, and wisper does not manage your DNS - "
                    + "add each hostname you need instead, such as app.example.com and "
                    + "api.example.com.");
        }
        // A trailing dot is the fully-qualified form and means the same name. Dropping it
        // here rather than storing both is what keeps the unique index meaningful.
        String withoutRootDot = trimmed.endsWith(".")
                ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (withoutRootDot.isEmpty()) {
            throw reject("\"" + trimmed + "\" is not a hostname.");
        }
        if (looksLikeAnAddress(withoutRootDot)) {
            throw reject("That is an IP address, not a hostname. Certificates are issued to "
                    + "names, so point a name at this service instead.");
        }

        String ascii = toAsciiLabels(withoutRootDot);
        if (ascii.length() > MAX_LENGTH) {
            throw reject("A hostname is at most " + MAX_LENGTH + " characters; that one is "
                    + ascii.length() + ".");
        }
        if (!ascii.contains(".")) {
            throw reject("\"" + ascii + "\" has no domain part. A hostname needs at least one "
                    + "dot, such as " + ascii + ".example.com.");
        }
        if (!ascii.matches(SHAPE)) {
            throw reject("\"" + ascii + "\" is not a valid hostname. Use letters, digits, "
                    + "hyphens and dots, starting and ending with a letter or digit.");
        }
        checkLabels(ascii);
        checkIssuable(ascii);
        return ascii;
    }

    /** Whether a string is written as a wildcard, which is the same fact as its kind. */
    public static boolean isWildcard(String candidate) {
        return candidate != null && candidate.startsWith("*.");
    }

    /**
     * The registrable part of a hostname, as far as this can be known without a public
     * suffix list: everything after the first label.
     *
     * <p>Used to place the challenge record for a name whose owner cannot create a
     * {@code _wisper-challenge} label under the full hostname. Approximate on purpose - the
     * exact answer needs a list that has to be kept up to date, and the challenge lookup
     * tries the full hostname first anyway.
     */
    public static String parent(String hostname) {
        int firstDot = hostname.indexOf('.');
        return firstDot < 0 || firstDot == hostname.length() - 1
                ? hostname : hostname.substring(firstDot + 1);
    }

    /**
     * Every label converted to its A-label form and lower-cased.
     *
     * <p>{@link IDN#toASCII(String)} enforces the label rules itself and reports them as
     * "the label in the input is too long", naming neither the label nor the limit. When it
     * refuses, {@link #checkLabels} is run over the input first so the customer is told
     * which part is wrong and what the limit is; only a refusal that rule cannot explain
     * falls through to the converter's own wording.
     */
    private static String toAsciiLabels(String hostname) {
        try {
            return IDN.toASCII(hostname).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException notConvertible) {
            checkLabels(hostname.toLowerCase(Locale.ROOT));
            throw reject("\"" + hostname + "\" is not a hostname wisper can use: "
                    + notConvertible.getMessage() + ".");
        }
    }

    private static void checkLabels(String hostname) {
        for (String label : hostname.split("\\.", -1)) {
            if (label.isEmpty()) {
                throw reject("\"" + hostname + "\" has an empty part between two dots.");
            }
            if (label.length() > MAX_LABEL_LENGTH) {
                throw reject("\"" + label + "\" is longer than the " + MAX_LABEL_LENGTH
                        + " characters a hostname allows between dots.");
            }
            if (label.startsWith("-") || label.endsWith("-")) {
                throw reject("\"" + label + "\" starts or ends with a hyphen, which a hostname "
                        + "may not do.");
            }
        }
    }

    private static void checkIssuable(String hostname) {
        for (String suffix : UNISSUABLE_SUFFIXES) {
            if (hostname.equals(suffix) || hostname.endsWith("." + suffix)) {
                throw reject("No certificate authority issues for ." + suffix
                        + " names, so this hostname could never be served over HTTPS. Use a name "
                        + "under a domain you own.");
            }
        }
    }

    /**
     * Whether the string is an address literal rather than a name.
     *
     * <p>An IPv6 literal contains a colon, which no hostname does. An IPv4 literal is four
     * numeric labels - and a name whose last label is entirely numeric cannot exist either,
     * which is the cheap and complete test.
     */
    private static boolean looksLikeAnAddress(String candidate) {
        if (candidate.indexOf(':') >= 0) {
            return true;
        }
        List<String> labels = List.of(candidate.split("\\.", -1));
        String last = labels.get(labels.size() - 1);
        return !last.isEmpty() && last.chars().allMatch(Character::isDigit);
    }

    private static RequestRejected reject(String message) {
        return new RequestRejected("hostname", message);
    }
}
