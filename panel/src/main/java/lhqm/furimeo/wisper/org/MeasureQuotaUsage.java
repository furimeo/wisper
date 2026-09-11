package lhqm.furimeo.wisper.org;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Counts what an organization is already using, one query per resource.
 *
 * <p>Usage is measured, never accumulated. There is no {@code used_projects} column
 * anywhere in the schema, and that is the point: a counter is a second copy of a fact,
 * and the day a delete misses it the platform starts refusing a customer something they
 * are entitled to, or - worse - stops refusing them anything at all. Counting the rows
 * cannot drift from the rows.
 *
 * <p>Each resource is one scalar SQL fragment, written once and used two ways: on its own
 * for {@link #of}, and as thirteen sub-selects in one statement for {@link #all}, which is
 * what keeps the plan page to a single round trip without keeping two copies of the same
 * SQL in step by hand.
 *
 * <p>All the joins walk the ownership path from schema.md §3 -
 * {@code organization -> project -> service -> leaf} - because there is no denormalised
 * {@code organization_id} on the leaves to shortcut through.
 */
@Component
public class MeasureQuotaUsage {

    private static final Map<QuotaResource, String> FRAGMENTS = fragments();

    private static final String ALL_SQL = combined();

    private final JdbcClient jdbc;

    public MeasureQuotaUsage(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** How much of one resource is spoken for right now. */
    public long of(UUID organizationId, QuotaResource resource) {
        Long used = jdbc.sql(FRAGMENTS.get(resource))
                .param("organizationId", organizationId)
                .query(Long.class)
                .single();
        return used == null ? 0L : used;
    }

    /** Every resource in one statement, for the screen that draws thirteen bars. */
    public Map<QuotaResource, Long> all(UUID organizationId) {
        return jdbc.sql(ALL_SQL)
                .param("organizationId", organizationId)
                .query(MeasureQuotaUsage::mapRow)
                .single();
    }

    private static Map<QuotaResource, Long> mapRow(ResultSet row, int rowNumber)
            throws SQLException {
        Map<QuotaResource, Long> used = new EnumMap<>(QuotaResource.class);
        QuotaResource[] resources = QuotaResource.values();
        for (int i = 0; i < resources.length; i++) {
            used.put(resources[i], row.getLong(i + 1));
        }
        return used;
    }

    /**
     * One row, thirteen columns, in {@link QuotaResource} declaration order - which is
     * the order {@link #mapRow} reads them back in, so the two cannot fall out of step
     * when a resource is added.
     */
    private static String combined() {
        StringBuilder sql = new StringBuilder("SELECT ");
        QuotaResource[] resources = QuotaResource.values();
        for (int i = 0; i < resources.length; i++) {
            if (i > 0) {
                sql.append(",\n       ");
            }
            sql.append('(').append(FRAGMENTS.get(resources[i]).strip()).append(") AS used_")
                    .append(resources[i].name().toLowerCase(Locale.ROOT));
        }
        return sql.toString();
    }

    private static Map<QuotaResource, String> fragments() {
        EnumMap<QuotaResource, String> sql = new EnumMap<>(QuotaResource.class);

        sql.put(QuotaResource.PROJECT, """
                SELECT count(*) FROM project
                 WHERE organization_id = :organizationId
                """);

        // Archived services keep their rows and their data but are not charged for.
        sql.put(QuotaResource.SERVICE, """
                SELECT count(*) FROM service s
                  JOIN project p ON p.id = s.project_id
                 WHERE p.organization_id = :organizationId
                   AND s.archived_at IS NULL
                """);

        sql.put(QuotaResource.DOMAIN, """
                SELECT count(*) FROM domain d
                  JOIN service s ON s.id = d.service_id
                  JOIN project p ON p.id = s.project_id
                 WHERE p.organization_id = :organizationId
                """);

        // A database on its way out still occupies its name and its disk until the node
        // confirms; counting it is what stops delete-and-recreate being a way past the
        // limit.
        sql.put(QuotaResource.MANAGED_DATABASE, """
                SELECT count(*) FROM managed_database md
                  JOIN project p ON p.id = md.project_id
                 WHERE p.organization_id = :organizationId
                """);

        sql.put(QuotaResource.CRON_TASK, """
                SELECT count(*) FROM cron_task c
                  JOIN service s ON s.id = c.service_id
                  JOIN project p ON p.id = s.project_id
                 WHERE p.organization_id = :organizationId
                """);

        // Accepted members only. An outstanding invitation grants nothing, so charging
        // for it would bill a customer for a seat nobody can sit in.
        sql.put(QuotaResource.MEMBER, """
                SELECT count(*) FROM member
                 WHERE organization_id = :organizationId
                   AND accepted_at IS NOT NULL
                """);

        // Tokens confined to this organization, plus the account-wide tokens held by its
        // members - those reach this tenant's resources too, so they count against it.
        sql.put(QuotaResource.API_TOKEN, """
                SELECT count(*) FROM api_token t
                 WHERE t.revoked_at IS NULL
                   AND (t.expires_at IS NULL OR t.expires_at > now())
                   AND (t.organization_id = :organizationId
                        OR (t.organization_id IS NULL
                            AND EXISTS (SELECT 1 FROM member m
                                         WHERE m.organization_id = :organizationId
                                           AND m.account_id = t.account_id
                                           AND m.accepted_at IS NOT NULL)))
                """);

        // The space promised, not the space used: the node enforces size_bytes as an XFS
        // project quota whether or not the customer has filled it.
        sql.put(QuotaResource.VOLUME_BYTES, """
                SELECT coalesce(sum(v.size_bytes), 0) FROM volume v
                  JOIN service s ON s.id = v.service_id
                  JOIN project p ON p.id = s.project_id
                 WHERE p.organization_id = :organizationId
                """);

        sql.put(QuotaResource.MEMORY_BYTES, """
                SELECT coalesce(sum(s.memory_bytes), 0) FROM service s
                  JOIN project p ON p.id = s.project_id
                 WHERE p.organization_id = :organizationId
                   AND s.archived_at IS NULL
                """);

        sql.put(QuotaResource.CPU_MILLICORES, """
                SELECT coalesce(sum(s.cpu_millicores), 0) FROM service s
                  JOIN project p ON p.id = s.project_id
                 WHERE p.organization_id = :organizationId
                   AND s.archived_at IS NULL
                """);

        // Only snapshots that exist. A failed or expired restore point occupies nothing.
        sql.put(QuotaResource.BACKUP_BYTES, """
                SELECT coalesce(sum(size_bytes), 0) FROM restore_point
                 WHERE organization_id = :organizationId
                   AND state = 'AVAILABLE'
                """);

        sql.put(QuotaResource.RESTORE_POINT, """
                SELECT count(*) FROM restore_point
                 WHERE organization_id = :organizationId
                   AND state = 'AVAILABLE'
                """);

        // The one windowed resource: a rolling twenty-four hours, not a calendar day, so
        // a customer cannot spend tomorrow's allowance at one minute to midnight.
        sql.put(QuotaResource.DEPLOYMENTS_PER_DAY, """
                SELECT count(*) FROM deployment d
                  JOIN service s ON s.id = d.service_id
                  JOIN project p ON p.id = s.project_id
                 WHERE p.organization_id = :organizationId
                   AND d.created_at > now() - interval '24 hours'
                """);

        if (sql.size() != QuotaResource.values().length) {
            throw new IllegalStateException("Every QuotaResource needs a usage query; "
                    + sql.size() + " of " + QuotaResource.values().length + " have one");
        }
        return Map.copyOf(sql);
    }
}
