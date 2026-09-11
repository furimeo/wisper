package lhqm.furimeo.wisper.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The secrets on a service, without their values.
 *
 * <p>A component of its own rather than a mapping step on top of
 * {@link SecretRepository}, because the guarantee is stronger when the column is not in
 * the {@code SELECT}. Mapping a row to a view that drops a field relies on nobody adding
 * the field back; a query that never reads it cannot be undone by a later edit somewhere
 * else, and the reviewer can see the whole promise in six lines of SQL.
 *
 * <p>{@code coalesce(last_rotated_at, created_at)} is the "last changed" column the screen
 * shows: a secret that has never been rotated was last changed when it was created, and
 * a blank cell there reads as "never set", which is the opposite of true.
 */
@Component
public class ListSecrets {

    private static final String SQL = """
            SELECT id, name, build_time,
                   coalesce(last_rotated_at, created_at) AS last_changed_at,
                   created_at
              FROM secret
             WHERE service_id = :serviceId
             ORDER BY name
            """;

    private final JdbcClient jdbc;

    public ListSecrets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every secret on the service, by name, values excluded at the query. */
    public List<SecretView> of(UUID serviceId) {
        if (serviceId == null) {
            return List.of();
        }
        return jdbc.sql(SQL)
                .param("serviceId", serviceId)
                .query(ListSecrets::map)
                .list();
    }

    private static SecretView map(ResultSet row, int rowNumber) throws SQLException {
        return new SecretView(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getBoolean("build_time"),
                instant(row, "last_changed_at"),
                instant(row, "created_at"));
    }

    /**
     * PgJDBC maps {@code timestamptz} to {@link OffsetDateTime}; asking it for an
     * {@link Instant} directly throws.
     */
    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
