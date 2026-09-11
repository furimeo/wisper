package lhqm.furimeo.wisper.service;

import java.util.Locale;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * The rules an environment variable's name has to satisfy, shared by {@code env_var} and
 * {@code secret}.
 *
 * <p>The two tables are separate because the difference between a readable value and one
 * that must never be shown back changes how it is stored, whether a GET returns it and
 * what an audit entry may say (schema.md §3). Their <em>names</em>, though, land in the
 * same place - one process environment - so they obey one rule and share one namespace.
 * That second half is enforced by the use-cases, not by the schema: two unique indexes,
 * one per table, would happily let {@code DATABASE_URL} exist as both.
 *
 * <p>{@code WISPER_*} is refused. The platform injects variables under that prefix and a
 * customer who could set one could overwrite what the node relies on. The CHECK in the
 * migration is case-sensitive; this is not, because {@code wisper_home} is a name that
 * gets typed by somebody who thinks they are being polite and produces a variable sitting
 * one shift key away from a platform one.
 */
public final class EnvVarName {

    /** Same as the {@code env_var_name_shape} and {@code secret_name_shape} CHECKs. */
    public static final String PATTERN = "^[A-Za-z_][A-Za-z0-9_]*$";

    /** Reserved for what the platform injects. */
    public static final String RESERVED_PREFIX = "WISPER_";

    /** Long enough for anything real; short enough that a paste accident is caught. */
    public static final int MAX_LENGTH = 256;

    private EnvVarName() {
    }

    /**
     * Checks a name and returns it trimmed.
     *
     * @param field the input's {@code name} attribute, so the message lands under the box
     *              the customer typed in - {@code "name"} on both forms today
     * @throws RequestRejected when it is empty, malformed, too long or reserved
     */
    public static String require(String raw, String field) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            throw new RequestRejected(field, "Give the variable a name.");
        }
        if (name.length() > MAX_LENGTH) {
            throw new RequestRejected(field,
                    "A variable name is at most " + MAX_LENGTH + " characters.");
        }
        if (!name.matches(PATTERN)) {
            throw new RequestRejected(field,
                    "A variable name uses letters, digits and underscores, and does not start "
                            + "with a digit. DATABASE_URL, not database-url.");
        }
        if (name.toUpperCase(Locale.ROOT).startsWith(RESERVED_PREFIX)) {
            throw new RequestRejected(field,
                    RESERVED_PREFIX + "* is reserved: the platform sets those itself and your "
                            + "service reads them. Pick another name.");
        }
        return name;
    }
}
