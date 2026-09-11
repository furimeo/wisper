package lhqm.furimeo.wisper.org;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the member list, with the person's name attached.
 *
 * <p>{@link Member} holds an {@code accountId} and nothing else about the person, because
 * cross-aggregate references are ids and not object graphs. A screen that shows a list of
 * account ids is useless, so the join happens once, in {@link ListMembers}, and produces
 * this.
 *
 * @param id          the membership row, which is what the role and remove buttons post
 * @param accountId   the person, so the page can mark "this is you"
 * @param email       their address
 * @param displayName their name
 * @param role        what they may do here
 * @param accepted    false while the invitation is outstanding
 * @param invitedAt   when they were asked
 * @param acceptedAt  when they joined; null while outstanding
 */
public record MemberView(
        UUID id,
        UUID accountId,
        String email,
        String displayName,
        MemberRole role,
        boolean accepted,
        Instant invitedAt,
        Instant acceptedAt) {

    /** Whether this row is the person looking at it, who may leave but not demote. */
    public boolean isSelf(UUID viewerAccountId) {
        return accountId.equals(viewerAccountId);
    }
}
