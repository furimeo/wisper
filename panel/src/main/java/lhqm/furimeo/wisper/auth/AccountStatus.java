package lhqm.furimeo.wisper.auth;

/**
 * Whether an account may sign in. Mirrors the {@code account.status} CHECK.
 *
 * <p>There is no {@code DELETED}: removing a person is a row deletion, which cascades to
 * their sessions and tokens and leaves the audit trail intact through {@code SET NULL}.
 * A tombstone status would be a second way to express the same thing, and the two would
 * eventually disagree.
 */
public enum AccountStatus {

    /** Normal. May sign in. */
    ACTIVE,

    /**
     * Barred from signing in, and every live session revoked at the moment of suspension.
     *
     * <p>Suspending a person does not touch the containers their organization runs -
     * that is an organization-level decision, and conflating the two takes a customer's
     * site down because one colleague was locked out.
     */
    SUSPENDED
}
