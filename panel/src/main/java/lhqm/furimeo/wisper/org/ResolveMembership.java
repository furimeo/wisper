package lhqm.furimeo.wisper.org;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The authorization check every other package makes before it touches anything.
 *
 * <p>There is exactly one join that establishes ownership, and it is this one:
 *
 * <pre>account -> member -> organization -> project -> service</pre>
 *
 * <p>The leaves carry no denormalised {@code organization_id}, deliberately (schema.md
 * §3), so this class is the only place that walks it. That is the point: a second way to
 * decide who owns a service is a second way to get it wrong, and the one that is wrong is
 * always the one written in a hurry next to the feature.
 *
 * <p><strong>Not a member is a 404, not a 403.</strong> A 403 on somebody else's resource
 * tells an attacker that the id exists, which is the enumeration oracle
 * {@link NotFoundException} avoids. Being a member with too small a role is different -
 * that is {@link PermissionDenied}, and it is raised by the {@code require*} methods on
 * {@link Membership}, not here.
 *
 * <p>An outstanding invitation resolves to nothing. Both queries filter on
 * {@code accepted_at IS NOT NULL}, so an invitation that was sent and never answered
 * grants no more access than a stranger has.
 */
@Component
public class ResolveMembership {

    private static final String BY_ORGANIZATION = """
            SELECT m.organization_id, m.account_id, m.role
              FROM member m
             WHERE m.account_id = :accountId
               AND m.organization_id = :organizationId
               AND m.accepted_at IS NOT NULL
            """;

    private static final String BY_SERVICE = """
            SELECT m.organization_id, m.account_id, m.role
              FROM service s
              JOIN project p ON p.id = s.project_id
              JOIN member m ON m.organization_id = p.organization_id
             WHERE s.id = :serviceId
               AND m.account_id = :accountId
               AND m.accepted_at IS NOT NULL
            """;

    private static final String BY_PROJECT = """
            SELECT m.organization_id, m.account_id, m.role
              FROM project p
              JOIN member m ON m.organization_id = p.organization_id
             WHERE p.id = :projectId
               AND m.account_id = :accountId
               AND m.accepted_at IS NOT NULL
            """;

    private final JdbcClient jdbc;

    public ResolveMembership(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What this account may do in this organization.
     *
     * @throws NotFoundException if there is no such organization, or the account is not
     *                           an accepted member of it
     */
    public Membership forAccount(UUID accountId, UUID organizationId) {
        return lookup(BY_ORGANIZATION, "organizationId", organizationId, accountId)
                .orElseThrow(() -> NotFoundException.of("organization", organizationId));
    }

    /**
     * What this account may do to this service, resolved through its project and
     * organization.
     *
     * @throws NotFoundException if there is no such service, or it belongs to an
     *                           organization the account is not an accepted member of
     */
    public Membership forService(UUID accountId, UUID serviceId) {
        return lookup(BY_SERVICE, "serviceId", serviceId, accountId)
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
    }

    /**
     * What this account may do to this project.
     *
     * <p>The middle of the same join. Present because {@code project} would otherwise
     * read the organization id itself and then call {@link #forAccount}, which is the
     * second ownership path this class exists to prevent.
     *
     * @throws NotFoundException if there is no such project, or it is not the account's
     */
    public Membership forProject(UUID accountId, UUID projectId) {
        return lookup(BY_PROJECT, "projectId", projectId, accountId)
                .orElseThrow(() -> NotFoundException.of("project", projectId));
    }

    /**
     * The same question without the exception, for a caller deciding whether to show a
     * link rather than whether to allow an action.
     */
    public Optional<Membership> optionally(UUID accountId, UUID organizationId) {
        return lookup(BY_ORGANIZATION, "organizationId", organizationId, accountId);
    }

    private Optional<Membership> lookup(String sql, String parameter, UUID subject,
                                        UUID accountId) {
        if (subject == null || accountId == null) {
            return Optional.empty();
        }
        return jdbc.sql(sql)
                .param(parameter, subject)
                .param("accountId", accountId)
                .query(ResolveMembership::map)
                .optional();
    }

    private static Membership map(ResultSet row, int rowNumber) throws SQLException {
        return new Membership(
                row.getObject("organization_id", UUID.class),
                row.getObject("account_id", UUID.class),
                MemberRole.valueOf(row.getString("role")));
    }
}
