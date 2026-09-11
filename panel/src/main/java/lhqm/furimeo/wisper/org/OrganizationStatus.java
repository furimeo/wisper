package lhqm.furimeo.wisper.org;

/**
 * Whether the tenant may still be written to. Mirrors the {@code organization.status}
 * CHECK exactly.
 *
 * <p>There is no third value on purpose. "Suspended" is the only state between active
 * and deleted that the platform can describe honestly, and a schema with
 * {@code PENDING}, {@code TRIAL} or {@code CLOSED} in it would be describing a billing
 * lifecycle this project deliberately does not have.
 */
public enum OrganizationStatus {

    /** Normal. Everything the plan allows may be created. */
    ACTIVE,

    /**
     * Nothing new may be created.
     *
     * <p>Suspension stops the panel writing on the customer's behalf; it does not stop
     * their containers. A suspended organization's sites keep serving until an operator
     * stops them explicitly, because taking a customer offline and refusing them a new
     * project are very different decisions and only one of them was made here.
     */
    SUSPENDED
}
