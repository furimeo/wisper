package lhqm.furimeo.wisper.auth;

/**
 * What an account may do on the platform itself. Mirrors the {@code account.platform_role}
 * CHECK, value for value.
 *
 * <p>This is deliberately not the same axis as {@code member.role}. Platform role answers
 * "may this person see the machines the platform is made of"; membership role answers
 * "what may this person do inside one tenant". An operator with {@link #ADMIN} is not
 * automatically an owner of anybody's organization, and a customer who owns three
 * organizations is still {@link #CUSTOMER} here.
 */
public enum PlatformRole {

    /** The platform operator. Granted {@code ROLE_ADMIN}, which is what gates /admin/**. */
    ADMIN,

    /** Everyone else. What they may do inside an organization comes from {@code member.role}. */
    CUSTOMER;

    /** The Spring Security authority this role grants, on top of {@code ROLE_USER}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
