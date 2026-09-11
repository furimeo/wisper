package lhqm.furimeo.wisper.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the trail, filtered and paged.
 *
 * <p>One statement, assembled from the predicates the caller actually set, so the four
 * indexes {@code audit_log} carries are the four access paths this produces:
 * {@code audit_log_recent_idx} for the platform list, {@code audit_log_by_organization_idx}
 * for a tenant's, {@code audit_log_by_target_idx} for one object's history and
 * {@code audit_log_by_node_idx} for a machine's timeline (schema.md §4).
 *
 * <p>Every value is a bound parameter. Nothing from a query string reaches the SQL text -
 * the filter names are fixed strings in this file and the sort order is not a parameter at
 * all, because there is exactly one useful order for an audit log and it is newest first.
 *
 * <p>Public rather than package-private: {@code /admin/audit} is here, but a service page
 * showing "what happened to this service" is {@code service}'s screen and needs
 * {@link AuditLogQuery#forTarget}.
 */
@Component
public class SearchAuditLog {

    private static final String COLUMNS = """
            SELECT id, occurred_at, organization_id, actor_kind, actor_account_id, node_id,
                   actor_label, action, target_kind, target_id, target_label, outcome,
                   remote_address, request_id, detail
              FROM audit_log
            """;

    private static final String COUNT = "SELECT count(*) FROM audit_log\n";

    private final JdbcClient jdbc;

    public SearchAuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One page of matching entries, newest first, with the full count behind it.
     *
     * <p>Two statements rather than a window function. The count is over an index-only
     * range in every filtered case, and a {@code count(*) OVER ()} on the page query would
     * make the unfiltered platform view - the one an operator opens most - pay for
     * counting the whole table on every row it returns.
     */
    @Transactional(readOnly = true)
    public AuditLogPage find(AuditLogQuery request) {
        AuditLogQuery query = request.sanitised();
        Filter filter = filterFor(query);

        List<AuditLogEntry> entries = bind(
                jdbc.sql(COLUMNS + filter.where()
                        + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset"),
                filter.values())
                .param("limit", query.limit())
                .param("offset", query.offset())
                .query(SearchAuditLog::map)
                .list();

        Long total = bind(jdbc.sql(COUNT + filter.where()), filter.values())
                .query(Long.class)
                .single();

        return new AuditLogPage(entries, total == null ? entries.size() : total, query.offset(),
                query.limit());
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec,
                                                 Map<String, Object> values) {
        JdbcClient.StatementSpec bound = spec;
        for (Map.Entry<String, Object> value : values.entrySet()) {
            bound = bound.param(value.getKey(), value.getValue());
        }
        return bound;
    }

    private static Filter filterFor(AuditLogQuery query) {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        add(clauses, values, "organization_id = :organizationId", "organizationId",
                query.organizationId());
        add(clauses, values, "actor_account_id = :accountId", "accountId", query.accountId());
        add(clauses, values, "node_id = :nodeId", "nodeId", query.nodeId());
        add(clauses, values, "action = :action", "action", query.action());
        add(clauses, values, "target_kind = :targetKind", "targetKind", query.targetKind());
        add(clauses, values, "target_id = :targetId", "targetId", query.targetId());
        add(clauses, values, "outcome = :outcome", "outcome",
                query.outcome() == null ? null : query.outcome().name());
        // PgJDBC refuses to infer a SQL type for an Instant parameter and throws before the
        // statement is sent, so a bound on occurred_at crosses as an OffsetDateTime. UTC,
        // which is the offset every instant in this schema is stored at.
        add(clauses, values, "occurred_at >= :from", "from", offsetOf(query.from()));
        add(clauses, values, "occurred_at < :to", "to", offsetOf(query.to()));
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        return new Filter(where, values);
    }

    private static OffsetDateTime offsetOf(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static void add(List<String> clauses, Map<String, Object> values, String clause,
                            String name, Object value) {
        if (value == null) {
            return;
        }
        clauses.add(clause);
        values.put(name, value);
    }

    private static AuditLogEntry map(ResultSet row, int rowNumber) throws SQLException {
        return new AuditLogEntry(
                row.getObject("id", UUID.class),
                instant(row, "occurred_at"),
                row.getObject("organization_id", UUID.class),
                AuditActorKind.valueOf(row.getString("actor_kind")),
                row.getObject("actor_account_id", UUID.class),
                row.getObject("node_id", UUID.class),
                row.getString("actor_label"),
                row.getString("action"),
                row.getString("target_kind"),
                row.getObject("target_id", UUID.class),
                row.getString("target_label"),
                AuditOutcome.valueOf(row.getString("outcome")),
                row.getString("remote_address"),
                row.getString("request_id"),
                row.getString("detail"));
    }

    /**
     * PgJDBC maps {@code timestamptz} to {@link OffsetDateTime}; asking it for an
     * {@link Instant} directly throws.
     */
    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** A {@code WHERE} clause and the values its placeholders need. */
    private record Filter(String where, Map<String, Object> values) {
    }
}
