package lhqm.furimeo.wisper.org;

import java.util.UUID;

/**
 * The answer to "may this account act on this organization, and how far".
 *
 * <p>Produced by {@link ResolveMembership} and passed into every use-case that changes
 * something a tenant owns. It exists as a value rather than as three loose parameters
 * because the three travel together through six packages, and because a method that
 * takes a {@code Membership} cannot be called by a caller who never looked one up.
 *
 * <p>An instance always represents an <strong>accepted</strong> membership. An
 * outstanding invitation never becomes one of these; {@link ResolveMembership} refuses
 * it the same way it refuses a stranger.
 *
 * @param organizationId the tenant this permission is about
 * @param accountId      whose permission it is
 * @param role           what they may do
 */
public record Membership(UUID organizationId, UUID accountId, MemberRole role) {

    public Membership {
        if (organizationId == null || accountId == null || role == null) {
            throw new IllegalArgumentException("A membership needs an organization, an account "
                    + "and a role");
        }
    }

    /** Deploy, files, terminal, databases: everything that changes a customer's system. */
    public boolean canWrite() {
        return role.canWrite();
    }

    /** Membership, roles, organization settings. */
    public boolean canAdminister() {
        return role.canAdminister();
    }

    public boolean isOwner() {
        return role == MemberRole.OWNER;
    }

    /**
     * Refuses the caller unless they may change something.
     *
     * @param action the dotted audit action being attempted, used in the message so the
     *               refusal names what was refused
     * @throws PermissionDenied for a {@code VIEWER}
     */
    public void requireWrite(String action) {
        if (!canWrite()) {
            throw new PermissionDenied(action, role, "write access");
        }
    }

    /**
     * Refuses the caller unless they may administer the organization.
     *
     * @throws PermissionDenied for a {@code DEVELOPER} or a {@code VIEWER}
     */
    public void requireAdministration(String action) {
        if (!canAdminister()) {
            throw new PermissionDenied(action, role, "an owner or an administrator");
        }
    }

    /**
     * Refuses the caller unless they own the organization.
     *
     * @throws PermissionDenied for anyone who is not an {@code OWNER}
     */
    public void requireOwnership(String action) {
        if (!isOwner()) {
            throw new PermissionDenied(action, role, "an owner");
        }
    }
}
