package lhqm.furimeo.wisper.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/** Reads and writes the {@code member} table. */
public interface MemberRepository extends ListCrudRepository<Member, UUID> {

    /**
     * The membership row, accepted or not.
     *
     * <p>Callers deciding an authorization question must check {@link Member#isAccepted()}
     * themselves, or use {@link ResolveMembership}, which does it for them. This method
     * returns the invitation too because {@link AcceptInvite} needs it and because
     * {@link InviteMember} has to tell "already a member" from "already invited".
     */
    Optional<Member> findByOrganizationIdAndAccountId(UUID organizationId, UUID accountId);

    /** Everyone in the organization, owners first, then by when they joined. */
    @Query("""
            SELECT * FROM member
             WHERE organization_id = :organizationId
             ORDER BY CASE role
                        WHEN 'OWNER'     THEN 0
                        WHEN 'ADMIN'     THEN 1
                        WHEN 'DEVELOPER' THEN 2
                        ELSE                  3
                      END,
                      created_at
            """)
    List<Member> findByOrganization(@Param("organizationId") UUID organizationId);

    /**
     * How many owners are left.
     *
     * <p>The guard behind every role change and every removal: an organization with no
     * owner is one nobody can ever administer again, and no screen in the panel can undo
     * it. Backed by {@code member_owners_idx}.
     */
    @Query("""
            SELECT count(*) FROM member
             WHERE organization_id = :organizationId
               AND role = 'OWNER'
               AND accepted_at IS NOT NULL
            """)
    long countOwners(@Param("organizationId") UUID organizationId);

    /** Accepted members, which is what the {@code MEMBER} quota counts. */
    @Query("""
            SELECT count(*) FROM member
             WHERE organization_id = :organizationId
               AND accepted_at IS NOT NULL
            """)
    long countAccepted(@Param("organizationId") UUID organizationId);
}
