package lhqm.furimeo.wisper.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/** Reads and writes the {@code organization} table. */
public interface OrganizationRepository extends ListCrudRepository<Organization, UUID> {

    Optional<Organization> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /**
     * The organizations an account actually belongs to, for the switcher and the list
     * page.
     *
     * <p>Filtered on {@code accepted_at IS NOT NULL}: an outstanding invitation is not
     * membership, and this is the query whose index -
     * {@code member_by_account_idx} - was created partial for exactly that predicate.
     */
    @Query("""
            SELECT o.* FROM organization o
              JOIN member m ON m.organization_id = o.id
             WHERE m.account_id = :accountId
               AND m.accepted_at IS NOT NULL
             ORDER BY o.name
            """)
    List<Organization> findAcceptedFor(@Param("accountId") UUID accountId);

    /**
     * Invitations waiting for this account to answer.
     *
     * <p>Shown on the organization list, because an invitation nobody is told about is an
     * invitation nobody accepts.
     */
    @Query("""
            SELECT o.* FROM organization o
              JOIN member m ON m.organization_id = o.id
             WHERE m.account_id = :accountId
               AND m.accepted_at IS NULL
             ORDER BY m.invited_at
            """)
    List<Organization> findInvitationsFor(@Param("accountId") UUID accountId);

    /** The admin list, newest tenant first. */
    @Query("SELECT * FROM organization ORDER BY created_at DESC")
    List<Organization> findAllNewestFirst();

    /** How many tenants a plan is holding, which is why it cannot be deleted. */
    long countByPlanId(UUID planId);
}
