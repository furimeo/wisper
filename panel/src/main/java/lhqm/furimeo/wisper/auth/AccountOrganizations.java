package lhqm.furimeo.wisper.auth;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which organizations an account actually belongs to.
 *
 * <p>This is the authorization join docs/contracts/schema.md names -
 * {@code account -> member -> organization} - read from the one package whose job is
 * deciding who somebody is. It exists here rather than in {@code org} because the only
 * caller is {@link IssueApiToken}, which has to refuse a token scoped to an organization
 * the owner is not in, and because a token's scope is an identity question rather than a
 * tenancy one.
 *
 * <p>Read-only, and deliberately not a repository over a {@code Member} aggregate: the
 * {@code org} package owns that record, and two mappings of one table are two places for
 * its column list to be wrong. A {@code JdbcClient} query names the three columns it
 * needs and nothing else.
 *
 * <p>An outstanding invitation - {@code accepted_at IS NULL} - grants nothing, which is
 * why every query here filters on it.
 */
@Component
public class AccountOrganizations {

    private final JdbcClient jdbcClient;

    public AccountOrganizations(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** The organizations to offer when scoping a token, in a stable order. */
    public List<AccountOrganization> forAccount(UUID accountId) {
        return jdbcClient.sql("""
                        SELECT o.id, o.name, o.slug
                          FROM member m
                          JOIN organization o ON o.id = m.organization_id
                         WHERE m.account_id = :accountId
                           AND m.accepted_at IS NOT NULL
                         ORDER BY o.name
                        """)
                .param("accountId", accountId)
                .query((rs, row) -> new AccountOrganization(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("slug")))
                .list();
    }

    /** Whether the account is an accepted member of that organization. */
    public boolean isMember(UUID accountId, UUID organizationId) {
        return jdbcClient.sql("""
                        SELECT count(*)
                          FROM member
                         WHERE account_id = :accountId
                           AND organization_id = :organizationId
                           AND accepted_at IS NOT NULL
                        """)
                .param("accountId", accountId)
                .param("organizationId", organizationId)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * One organization, as much of it as a token form needs.
     *
     * @param slug what appears in URLs, so the form can show the same word the rest of
     *             the panel does
     */
    public record AccountOrganization(UUID id, String name, String slug) {
    }
}
