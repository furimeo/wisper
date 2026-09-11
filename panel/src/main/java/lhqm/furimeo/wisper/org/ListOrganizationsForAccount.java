package lhqm.furimeo.wisper.org;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every organization one account can see, accepted or merely invited, in one query.
 *
 * <p>This runs on every page render - it is what fills the organization switcher - so it
 * is one statement and it returns the invitations in the same pass. Two queries, one for
 * memberships and one for invitations, would be two round trips per page to answer a
 * question the same join already answers.
 */
@Component
public class ListOrganizationsForAccount {

    private static final String SQL = """
            SELECT o.id, o.name, o.slug, o.status, o.suspension_reason,
                   m.role, m.accepted_at, m.invited_at
              FROM organization o
              JOIN member m ON m.organization_id = o.id
             WHERE m.account_id = :accountId
             ORDER BY (m.accepted_at IS NULL), o.name
            """;

    private final JdbcClient jdbc;

    public ListOrganizationsForAccount(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Memberships first, then outstanding invitations; both alphabetical. */
    public List<OrganizationSummary> all(UUID accountId) {
        if (accountId == null) {
            return List.of();
        }
        return jdbc.sql(SQL)
                .param("accountId", accountId)
                .query(ListOrganizationsForAccount::map)
                .list();
    }

    /** The ones the account is actually a member of. */
    public List<OrganizationSummary> accepted(UUID accountId) {
        return all(accountId).stream().filter(OrganizationSummary::accepted).toList();
    }

    /** The ones waiting for an answer. */
    public List<OrganizationSummary> invitations(UUID accountId) {
        return all(accountId).stream().filter(summary -> !summary.accepted()).toList();
    }

    private static OrganizationSummary map(ResultSet row, int rowNumber) throws SQLException {
        Instant acceptedAt = instant(row, "accepted_at");
        return new OrganizationSummary(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getString("slug"),
                MemberRole.valueOf(row.getString("role")),
                OrganizationStatus.valueOf(row.getString("status")),
                row.getString("suspension_reason"),
                acceptedAt != null,
                instant(row, "invited_at"));
    }

    /**
     * PgJDBC maps {@code timestamptz} to {@link OffsetDateTime}, not to {@link Instant} -
     * asking it for an {@code Instant} directly throws. One conversion in one place.
     */
    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
