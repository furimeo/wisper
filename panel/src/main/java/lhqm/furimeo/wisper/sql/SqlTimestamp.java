package lhqm.furimeo.wisper.sql;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Binds an {@link Instant} to a {@code timestamptz} parameter.
 *
 * <h2>Why this has to exist</h2>
 *
 * <p>PgJDBC cannot bind an {@code Instant}. It refuses to infer a SQL type for one and
 * throws {@code Can't infer the SQL type to use for an instance of java.time.Instant}
 * before the statement reaches the server. Spring Data JDBC's repositories convert it on
 * the way through, which is why most of the application never notices - but hand-written
 * SQL run through {@code JdbcClient} passes the value straight to the driver.
 *
 * <h2>Why it is shared rather than private</h2>
 *
 * <p>Five classes had worked this out independently and carried their own copy of the
 * conversion. Nine others did not, and every one of those was a statement that compiled,
 * passed its unit tests, and threw the first time it was executed against a real
 * PostgreSQL - which for the node package meant the first time a node ever enrolled.
 *
 * <p>Domain code keeps using {@code Instant}. This is a boundary conversion and belongs
 * at the boundary, not in the domain.
 */
public final class SqlTimestamp {

    private SqlTimestamp() {
    }

    /**
     * @param value may be null, for a nullable {@code timestamptz} column
     * @return the same moment at UTC, which is the offset every instant in this schema is
     *         stored at
     */
    public static OffsetDateTime at(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
