package lhqm.furimeo.wisper.backup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads destinations for a screen, without the two encrypted columns.
 *
 * <p>The column list is written out rather than {@code SELECT *} precisely so that
 * {@code secret_access_key} and {@code archive_passphrase} are absent by construction. A
 * star select here would put an envelope into a {@link DestinationView} the day somebody
 * adds a field to the record, and nothing would fail - it would simply start appearing in
 * the page source.
 *
 * <p>The counts are what the delete button needs. {@code ON DELETE RESTRICT} from both
 * {@code backup} and {@code restore_point} means a destination in use cannot be removed, and
 * a screen that offers the button anyway is a screen that answers with a constraint
 * violation.
 */
@Component
public class ListBackupDestinations {

    private static final String COLUMNS = """
            SELECT d.id, d.organization_id, d.name, d.kind, d.endpoint, d.region, d.bucket,
                   d.path_prefix, d.access_key_id, d.storage_class, d.local_path, d.enabled,
                   d.last_checked_at, d.last_check_error,
                   (SELECT count(*) FROM backup b
                     WHERE b.destination_id = d.id) AS policy_count,
                   (SELECT count(*) FROM restore_point rp
                     WHERE rp.destination_id = d.id
                       AND rp.state = 'AVAILABLE') AS snapshot_count,
                   (SELECT coalesce(sum(rp.size_bytes), 0) FROM restore_point rp
                     WHERE rp.destination_id = d.id
                       AND rp.state = 'AVAILABLE') AS stored_bytes
              FROM backup_destination d
            """;

    private static final String VISIBLE_TO = COLUMNS + """
             WHERE d.organization_id = :organizationId OR d.organization_id IS NULL
             ORDER BY d.organization_id NULLS LAST, d.name
            """;

    private static final String PLATFORM_WIDE = COLUMNS + """
             WHERE d.organization_id IS NULL
             ORDER BY d.name
            """;

    private final JdbcClient jdbc;

    public ListBackupDestinations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What a customer's screen shows: their own destinations, and the platform-wide ones
     * they may choose but not change.
     */
    public List<DestinationView> visibleTo(UUID organizationId) {
        return jdbc.sql(VISIBLE_TO)
                .param("organizationId", organizationId)
                .query((row, number) -> map(row, organizationId))
                .list();
    }

    /** What the operator screen shows: the destinations every tenant can use. */
    public List<DestinationView> platformWide() {
        return jdbc.sql(PLATFORM_WIDE)
                // Null scope: the operator screen owns exactly the rows with no owner, so
                // every row it lists is editable.
                .query((row, number) -> map(row, null))
                .list();
    }

    private static DestinationView map(ResultSet row, UUID callerScope) throws SQLException {
        UUID owner = row.getObject("organization_id", UUID.class);
        boolean platformWide = owner == null;
        boolean editable = owner == null ? callerScope == null : owner.equals(callerScope);
        return new DestinationView(
                row.getObject("id", UUID.class),
                row.getString("name"),
                DestinationKind.valueOf(row.getString("kind")),
                platformWide,
                editable,
                row.getBoolean("enabled"),
                row.getString("endpoint"),
                row.getString("region"),
                row.getString("bucket"),
                row.getString("path_prefix"),
                row.getString("access_key_id"),
                row.getString("storage_class"),
                row.getString("local_path"),
                instant(row, "last_checked_at"),
                row.getString("last_check_error"),
                row.getLong("policy_count"),
                row.getLong("snapshot_count"),
                row.getLong("stored_bytes"));
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
