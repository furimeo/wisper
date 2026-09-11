package lhqm.furimeo.wisper.org;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * One account's membership of one organization, and what it may do there.
 *
 * <p>An invitation is a row with {@code acceptedAt} null. It grants nothing at all -
 * every read of this table that decides an authorization question filters on
 * {@code accepted_at IS NOT NULL}, and the {@code MEMBER} quota counts accepted rows
 * only. Keeping the invitation in the same table rather than a separate one is what
 * makes "you are already invited" a unique-index violation instead of a check somebody
 * forgets to write.
 *
 * @param id                  generated in Java before the insert
 * @param organizationId      the tenant
 * @param accountId           the person
 * @param role                what they may do once they have accepted
 * @param invitedByAccountId  who asked them; null once that account is deleted
 * @param invitedAt           when the invitation was sent
 * @param acceptedAt          null while the invitation is outstanding
 * @param version             null means new
 */
public record Member(
        @Id UUID id,
        UUID organizationId,
        UUID accountId,
        MemberRole role,
        UUID invitedByAccountId,
        Instant invitedAt,
        Instant acceptedAt,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /** The first member of a new organization: its owner, accepted on the spot. */
    public static Member founding(UUID id, UUID organizationId, UUID accountId, Instant at) {
        return new Member(id, organizationId, accountId, MemberRole.OWNER, null, at, at,
                null, null, null);
    }

    /** An outstanding invitation. Grants nothing until {@link #accepted(Instant)}. */
    public static Member invited(UUID id, UUID organizationId, UUID accountId, MemberRole role,
                                 UUID invitedByAccountId, Instant at) {
        return new Member(id, organizationId, accountId, role, invitedByAccountId, at, null,
                null, null, null);
    }

    public boolean isAccepted() {
        return acceptedAt != null;
    }

    /** The invitation was taken up. */
    public Member accepted(Instant at) {
        return new Member(id, organizationId, accountId, role, invitedByAccountId, invitedAt, at,
                createdAt, updatedAt, version);
    }

    /** The same person, a different role. */
    public Member withRole(MemberRole newRole) {
        return new Member(id, organizationId, accountId, newRole, invitedByAccountId, invitedAt,
                acceptedAt, createdAt, updatedAt, version);
    }

    /** The membership this row grants, or empty while the invitation is outstanding. */
    public Membership membership() {
        if (!isAccepted()) {
            throw new IllegalStateException("An unaccepted invitation grants no membership");
        }
        return new Membership(organizationId, accountId, role);
    }
}
