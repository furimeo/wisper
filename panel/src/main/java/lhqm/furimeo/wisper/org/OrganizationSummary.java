package lhqm.furimeo.wisper.org;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of the organization switcher: enough to draw it, and nothing more.
 *
 * <p>A separate shape from {@link Organization} because the switcher needs the viewer's
 * role, which is not on the organization row, and does not need the plan or the
 * timestamps, which would then be serialised into every page in the panel.
 *
 * @param id          the organization
 * @param name        what it is called
 * @param slug        its URL fragment
 * @param role        what the account looking at it may do there
 * @param status      active or suspended, so the chrome can say so on every screen
 * @param suspensionReason why, when it is suspended; null otherwise
 * @param accepted    false while this is still an invitation the account has not answered
 * @param invitedAt   when the invitation was sent, for the "pending" list ordering
 */
public record OrganizationSummary(
        UUID id,
        String name,
        String slug,
        MemberRole role,
        OrganizationStatus status,
        String suspensionReason,
        boolean accepted,
        Instant invitedAt) {

    public boolean isSuspended() {
        return status == OrganizationStatus.SUSPENDED;
    }

    /** The membership this line represents, for a caller that already has the row. */
    public Membership membershipFor(UUID accountId) {
        return new Membership(id, accountId, role);
    }
}
