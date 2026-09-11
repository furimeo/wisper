package lhqm.furimeo.wisper.org;

import java.util.Locale;

/**
 * What an account may do inside one organization. Mirrors the {@code member.role} CHECK
 * exactly, and is declared in descending order of authority so that
 * {@link #outranks(MemberRole)} is an ordinal comparison rather than a table of cases.
 *
 * <p>Four roles rather than two because the two interesting boundaries are different
 * ones: "may change who else is here" separates {@link #ADMIN} from {@link #DEVELOPER},
 * and "may touch a running system at all" separates {@link #DEVELOPER} from
 * {@link #VIEWER}. Collapsing either boundary means somebody who was given read access
 * to a dashboard also has a shell inside the container.
 */
public enum MemberRole {

    /**
     * Everything, including deleting the organization, transferring ownership and
     * changing another owner's role. There is always at least one.
     */
    OWNER,

    /** Everything except deleting the organization and touching an {@link #OWNER}. */
    ADMIN,

    /**
     * Deploy, terminal, files, databases, backups. No membership change, no plan change,
     * no organization-level settings.
     */
    DEVELOPER,

    /**
     * Read only: overview, logs and metrics. Explicitly <em>not</em> the terminal and
     * <em>not</em> the file manager - both are write access wearing a read-only name.
     */
    VIEWER;

    /** Whether this role may create, change or delete anything a customer owns. */
    public boolean canWrite() {
        return this != VIEWER;
    }

    /** Whether this role may change membership, roles and organization settings. */
    public boolean canAdminister() {
        return this == OWNER || this == ADMIN;
    }

    /** Whether this role is strictly more privileged than {@code other}. */
    public boolean outranks(MemberRole other) {
        return ordinal() < other.ordinal();
    }

    /** Whether this role is {@code other} or better. */
    public boolean atLeast(MemberRole other) {
        return ordinal() <= other.ordinal();
    }

    /** The label a screen shows: {@code Developer}, not {@code DEVELOPER}. */
    public String label() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }
}
