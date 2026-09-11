package lhqm.furimeo.wisper.project;

import java.util.Locale;

/**
 * The URL fragment rule the customer-facing tables share.
 *
 * <p>{@code project.slug}, {@code service.slug}, {@code volume.name} and
 * {@code cron_task.name} are all checked by a CHECK constraint of the same shape, and
 * all four are typed by a person into a form that offers to fill them in from the
 * display name. Writing that derivation four times means four slightly different
 * answers to "what happens to an accented character", and the one that is wrong is
 * discovered as a constraint violation rather than as a message under the input.
 *
 * <p>Two lengths, because the constraints differ by one character:
 *
 * <ul>
 * <li>{@link #PATTERN} - 2 to 63 characters, for {@code project.slug} and
 *     {@code service.slug}. These appear in URLs, where a single character is not worth
 *     the ambiguity it buys.</li>
 * <li>{@link #SHORT_PATTERN} - 1 to 63, for {@code volume.name} and
 *     {@code cron_task.name}. A volume called {@code w} is legitimate; nothing resolves
 *     a route by it.</li>
 * </ul>
 *
 * <p>Nothing here guesses at uniqueness. A slug that is already taken is a question for
 * the repository that owns the table, and the answer is a message naming the field, not
 * a number quietly appended to what the customer typed.
 */
public final class Slug {

    /** Longest a slug may be, in every table that has one. */
    public static final int MAX_LENGTH = 63;

    /** {@code project.slug} and {@code service.slug}: 2 to 63 characters. */
    public static final String PATTERN = "^[a-z0-9][a-z0-9-]{1,62}$";

    /** {@code volume.name} and {@code cron_task.name}: 1 to 63 characters. */
    public static final String SHORT_PATTERN = "^[a-z0-9][a-z0-9-]{0,62}$";

    private Slug() {
    }

    /**
     * The slug the customer asked for, or one derived from the display name when they
     * left the field empty.
     *
     * @param preferred   what was typed into the slug input; may be null or blank
     * @param derivedFrom the display name to fall back to
     * @return a normalised candidate, which the caller still has to validate
     */
    public static String of(String preferred, String derivedFrom) {
        String candidate = normalise(preferred);
        return candidate.isEmpty() ? normalise(derivedFrom) : candidate;
    }

    /**
     * Lower-cases, replaces every run of anything else with a single dash, and trims the
     * dashes off both ends.
     *
     * <p>Non-ASCII is dropped rather than transliterated. A transliteration table is a
     * language guess - {@code ö} is {@code oe} in German and {@code o} in Swedish - and
     * guessing wrong in a URL is worse than asking the customer to type an address.
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_LENGTH));
        boolean pendingDash = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = Character.toLowerCase(raw.charAt(i));
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!allowed) {
                pendingDash = true;
                continue;
            }
            // The separator and the character it separates are budgeted together. Testing
            // the length once per character instead would let the last pair straddle the
            // limit and hand back 64 characters, which every CHECK constraint using this
            // refuses - so a long name would be answered with "that address will not work"
            // rather than with a shortened address.
            boolean needsDash = pendingDash && !out.isEmpty();
            if (out.length() + (needsDash ? 2 : 1) > MAX_LENGTH) {
                break;
            }
            if (needsDash) {
                out.append('-');
            }
            pendingDash = false;
            out.append(c);
        }
        return out.toString();
    }

    /** Whether this is a usable {@code project.slug} or {@code service.slug}. */
    public static boolean isValid(String candidate) {
        return candidate != null && candidate.matches(PATTERN);
    }

    /** Whether this is a usable {@code volume.name} or {@code cron_task.name}. */
    public static boolean isShortValid(String candidate) {
        return candidate != null && candidate.matches(SHORT_PATTERN);
    }

    /**
     * The sentence a customer is shown when a slug does not fit, phrased for the field
     * it belongs to.
     *
     * @param minimum 2 for a slug in a URL, 1 for a name inside a service
     */
    public static String rule(int minimum) {
        return "Use " + minimum + " to " + MAX_LENGTH + " characters: lower-case letters, "
                + "digits and dashes, starting with a letter or a digit.";
    }

    /** Trims a display name and collapses its internal whitespace to single spaces. */
    public static String tidyName(String raw) {
        return raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
    }

    /** The lower-cased form, for comparing what was typed with what is stored. */
    public static String lower(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }
}
