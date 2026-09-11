package lhqm.furimeo.wisper.domain;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * The TXT-record challenge, and the exact strings the customer is shown.
 *
 * <p>Pointing A/AAAA at the node is the ordinary proof of ownership, but it cannot always
 * be given first: a hostname that is already serving a live site elsewhere cannot be
 * repointed until the new one is ready, and that is precisely the migration wisper has to
 * support. The TXT record proves control without moving any traffic.
 *
 * <p>The record name and the record value are produced here and nowhere else. If the page
 * told the customer one string and the lookup asked for another, verification would fail
 * for a customer who did exactly what they were told - a bug that looks like a DNS problem
 * and is not.
 */
public final class VerificationToken {

    /**
     * The label the challenge record goes under.
     *
     * <p>Underscore-prefixed, which is what stops it colliding with a real hostname: a
     * label beginning with {@code _} is reserved for exactly this kind of use and cannot be
     * a host.
     */
    public static final String LABEL = "_wisper-challenge";

    /**
     * The prefix inside the TXT value.
     *
     * <p>A domain often carries several verification records for several services. Naming
     * ours means the lookup can ignore everybody else's instead of matching a bare random
     * string anywhere in the set.
     */
    public static final String VALUE_PREFIX = "wisper-domain-verification=";

    /** 24 bytes of entropy: unguessable, and short enough to fit one TXT string. */
    private static final int TOKEN_BYTES = 24;

    private static final SecureRandom RANDOM = new SecureRandom();

    private VerificationToken() {
    }

    /**
     * A fresh token for a hostname that has just been added.
     *
     * <p>Minted on creation rather than on demand, so the instructions are on the screen
     * from the first moment and the customer never has to press a button to find out what
     * to publish. Never rotated: rotating would invalidate a record the customer may
     * already have published.
     */
    public static String issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The name the customer creates the TXT record at, for example {@code _wisper-challenge.app.example.com}. */
    public static String recordName(String hostname) {
        return LABEL + "." + hostname;
    }

    /**
     * The fallback name, one level up.
     *
     * <p>Some DNS interfaces will not create a record under a name that has no other
     * records, and some hosting panels only expose the zone apex. Accepting the record one
     * level up costs nothing - proving control of {@code example.com} is a stronger claim
     * than proving control of {@code app.example.com}, not a weaker one.
     */
    public static String parentRecordName(String hostname) {
        return LABEL + "." + Hostname.parent(hostname);
    }

    /** The value the customer pastes in. */
    public static String recordValue(String token) {
        return VALUE_PREFIX + token;
    }

    /**
     * Whether any of the TXT strings found at the challenge name carries this token.
     *
     * <p>Trimmed and compared case-insensitively on the prefix, because DNS interfaces
     * variously quote, pad and re-case what is typed into them. The token itself is
     * compared exactly - it is base64url, where case is meaning.
     */
    public static boolean isPresentIn(List<String> textRecords, String token) {
        if (token == null || token.isBlank() || textRecords == null) {
            return false;
        }
        String expected = recordValue(token);
        for (String record : textRecords) {
            if (record == null) {
                continue;
            }
            String trimmed = record.trim();
            if (trimmed.length() != expected.length()) {
                continue;
            }
            String prefix = trimmed.substring(0, VALUE_PREFIX.length());
            if (prefix.toLowerCase(Locale.ROOT).equals(VALUE_PREFIX)
                    && trimmed.substring(VALUE_PREFIX.length())
                            .equals(expected.substring(VALUE_PREFIX.length()))) {
                return true;
            }
        }
        return false;
    }
}
