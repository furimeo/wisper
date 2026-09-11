package lhqm.furimeo.wisper.database;

import java.util.Locale;
import java.util.UUID;

/**
 * Turns what a customer typed into a database name and a login both engines accept
 * unquoted.
 *
 * <h2>Why the tenant is in the name</h2>
 *
 * <p>A shared engine holds every tenant on the node. If the name were only what the
 * customer typed, the first person to create {@code app} would take it from everybody
 * else, and the refusal - "that name is in use" - would be a message about a stranger's
 * choices. So the organization's slug, which is globally unique, is the prefix. Two
 * tenants can then both have {@code app} and neither can find out about the other; a
 * second {@code app} inside one organization is a genuine collision, refused with a
 * sentence the customer can act on. That refusal is what {@link ProvisionDatabase}
 * checks for before it writes anything.
 *
 * <h2>Why the login is not the name</h2>
 *
 * <p>{@code managed_database_username_shape} allows 31 characters and
 * {@code managed_database_name_shape} allows 63, so a name that fits is a login that does
 * not. The login is therefore the name truncated with six hex characters of the row's own
 * id appended: it stays recognisable next to its database in {@code \du}, and it cannot
 * collide, because the id cannot.
 *
 * <p>Both shapes are the CHECK constraints in V25, written out here so a bad name is a
 * sentence under an input rather than a constraint violation three frames later.
 */
public final class DatabaseIdentifier {

    /** {@code managed_database_name_shape}: a letter, then 2 to 62 more. */
    public static final String NAME_PATTERN = "^[a-z][a-z0-9_]{2,62}$";

    /** {@code managed_database_username_shape}: a letter, then 2 to 30 more. */
    public static final String USERNAME_PATTERN = "^[a-z][a-z0-9_]{2,30}$";

    private static final int NAME_MAX = 63;
    private static final int USERNAME_MAX = 31;

    /** Leaves room for the underscore and the customer's own word after the prefix. */
    private static final int PREFIX_MAX = 24;

    /** Six hex characters of the id, plus the underscore before them. */
    private static final int SUFFIX_LENGTH = 7;

    private DatabaseIdentifier() {
    }

    /**
     * The database name for a request.
     *
     * @param organizationSlug the tenant's slug, globally unique, which becomes the prefix
     * @param requested        what the customer typed; blank becomes {@code db}
     * @return a name matching {@link #NAME_PATTERN}
     * @throws IllegalArgumentException if what was typed contains nothing usable - every
     *         character was punctuation, or the slug was empty
     */
    public static String nameFor(String organizationSlug, String requested) {
        String prefix = clip(sanitise(organizationSlug), PREFIX_MAX);
        String body = sanitise(requested);
        if (body.isEmpty()) {
            body = "db";
        }
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("A database name needs the organization's slug as "
                    + "its prefix, and this organization has none that survives sanitising");
        }
        String name = clip(prefix + "_" + body, NAME_MAX);
        // Trailing underscores are legal but read as a mistake, and they appear whenever
        // the clip above lands on one.
        name = trimUnderscores(name);
        if (!name.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException("\"" + requested + "\" cannot be turned into a "
                    + "database name; use letters, digits and underscores");
        }
        return name;
    }

    /**
     * The login that owns a database.
     *
     * @param databaseName the name {@link #nameFor} produced
     * @param databaseId   the row's id, whose first six hex characters make the login
     *                     unique without a lookup
     */
    public static String usernameFor(String databaseName, UUID databaseId) {
        String stem = trimUnderscores(clip(databaseName, USERNAME_MAX - SUFFIX_LENGTH));
        String suffix = databaseId.toString().replace("-", "").substring(0, 6);
        String username = stem.isEmpty() ? "u" + suffix : stem + "_" + suffix;
        if (!username.matches(USERNAME_PATTERN)) {
            throw new IllegalArgumentException("Cannot build a login for database \""
                    + databaseName + "\"");
        }
        return username;
    }

    /**
     * Whether what a customer typed can become a name at all, without building one.
     *
     * <p>For the form, which would rather say "letters, digits and underscores" under the
     * input than let the request reach a use-case and come back as an exception.
     */
    public static boolean isUsableRequest(String requested) {
        return !sanitise(requested).isEmpty();
    }

    /** The rule, in one sentence, for a message under an input. */
    public static String rule() {
        return "Use lower-case letters, digits and underscores; it has to start with a letter.";
    }

    /**
     * Lower-cases, replaces anything else with an underscore, collapses runs of them and
     * makes sure the result starts with a letter.
     *
     * <p>A leading digit is prefixed rather than dropped, because {@code 2024_reports}
     * losing its year would be a surprising name for a database to end up with.
     */
    private static String sanitise(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        boolean lastWasUnderscore = false;
        for (char c : raw.strip().toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                out.append(c);
                lastWasUnderscore = false;
            } else if (!lastWasUnderscore && !out.isEmpty()) {
                out.append('_');
                lastWasUnderscore = true;
            }
        }
        String cleaned = trimUnderscores(out.toString());
        if (cleaned.isEmpty()) {
            return "";
        }
        char first = cleaned.charAt(0);
        return first >= 'a' && first <= 'z' ? cleaned : "d" + cleaned;
    }

    private static String clip(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String trimUnderscores(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '_') {
            end--;
        }
        return value.substring(0, end);
    }
}
