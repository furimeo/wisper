package lhqm.furimeo.wisper.database;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * One customer database and the single login that owns it. Maps the
 * {@code managed_database} table, column for column.
 *
 * <p>Exactly one user per database, so there is no grant table and no membership to get
 * wrong; a customer who needs two credentials creates two databases. The name and the
 * user are unique within the engine, which is where a collision actually happens.
 *
 * <p>Two columns are the node's and are never written through this record:
 * {@code usedBytes} and {@code usedBytesMeasuredAt} come from a status batch and are
 * written by {@link TrackDatabaseSize} with a statement of its own (schema.md §2). Every
 * wither below copies them through untouched, so a save that follows a measurement cannot
 * quietly undo it.
 *
 * <p>{@code state} is the opposite: it is the panel's own decision, made partly from what
 * the node reported, and no status handler writes it directly.
 *
 * @param name        the real database name on the engine, prefixed with the tenant by
 *                    {@link DatabaseIdentifier} so two customers on a shared instance
 *                    cannot collide
 * @param dbUsername  the login. Named with the {@code db} prefix because {@code user} is
 *                    reserved in PostgreSQL and Spring Data JDBC quotes nothing for you
 * @param dbPassword  an encrypted envelope. The panel has to be able to read it back: it
 *                    shows the connection string and offers a rotate button
 * @param usedBytes   what the node last measured, or null before it has measured once.
 *                    Null is "not measured", which is not zero
 * @param version     null means new; see schema.md §1
 */
public record ManagedDatabase(
        @Id UUID id,
        UUID projectId,
        UUID databaseEngineId,

        String name,
        String dbUsername,
        String dbPassword,
        String dbCharset,
        String dbCollation,

        long quotaBytes,
        Long usedBytes,
        Instant usedBytesMeasuredAt,

        ManagedDatabaseState state,
        String lastError,
        Instant provisionedAt,
        Instant passwordRotatedAt,

        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /** A database that has been asked for and not yet created on a node. */
    public static ManagedDatabase requested(UUID id, UUID projectId, UUID engineId, String name,
                                            String username, String passwordEnvelope,
                                            String charset, String collation, long quotaBytes) {
        return new ManagedDatabase(id, projectId, engineId, name, username, passwordEnvelope,
                charset, collation, quotaBytes, null, null,
                ManagedDatabaseState.PENDING, null, null, null, null, null, null);
    }

    /** The node has created the database and the role, and the credential works. */
    public ManagedDatabase ready(Instant at) {
        return moved(ManagedDatabaseState.READY, null, at, passwordRotatedAt, dbPassword,
                quotaBytes);
    }

    /** A provision, a rotation or a drop did not work, and this is what to show. */
    public ManagedDatabase failed(String message) {
        return moved(ManagedDatabaseState.FAILED,
                message == null || message.isBlank() ? "The node reported a failure." : message,
                provisionedAt, passwordRotatedAt, dbPassword, quotaBytes);
    }

    /**
     * Records what the row is waiting for without moving it.
     *
     * <p>Distinct from {@link #failed} on purpose. A database whose node has not answered
     * is still being created, or still being dropped; the work is in the node's spec and
     * the panel is retrying. Marking it failed would tell a customer the operation could
     * not be done when in fact nobody has asked the machine yet.
     */
    public ManagedDatabase noting(String detail) {
        return moved(state, detail, provisionedAt, passwordRotatedAt, dbPassword, quotaBytes);
    }

    /**
     * Over its quota.
     *
     * <p>Nothing is blocked by this. Neither engine enforces a per-database ceiling, so
     * the panel's part is to say so plainly and offer the two ways out; dropping data
     * because it grew is not one of them.
     */
    public ManagedDatabase suspended(String message) {
        return moved(ManagedDatabaseState.SUSPENDED, message, provisionedAt, passwordRotatedAt,
                dbPassword, quotaBytes);
    }

    /** Back under the limit, or the limit was raised. */
    public ManagedDatabase resumed(Instant at) {
        return moved(ManagedDatabaseState.READY, null,
                provisionedAt == null ? at : provisionedAt, passwordRotatedAt, dbPassword,
                quotaBytes);
    }

    /**
     * On its way out.
     *
     * <p>The grant leaves the node's spec at this point - {@code BuildDatabaseSpecs}
     * filters this state out - so the node removes it on its next pass even if the
     * explicit drop command never arrives.
     */
    public ManagedDatabase deleting() {
        return moved(ManagedDatabaseState.DELETING, lastError, provisionedAt, passwordRotatedAt,
                dbPassword, quotaBytes);
    }

    /**
     * A new password, recorded only once the engine has accepted it.
     *
     * <p>Writing it before the node confirms would leave the panel displaying a
     * credential that does not work while the old one still does - which is the opposite
     * of what somebody rotating a leaked password asked for.
     */
    public ManagedDatabase withPassword(String envelope, Instant at) {
        return moved(state == ManagedDatabaseState.FAILED ? ManagedDatabaseState.READY : state,
                null, provisionedAt, at, envelope, quotaBytes);
    }

    /** A new storage ceiling. */
    public ManagedDatabase withQuota(long newQuotaBytes) {
        return moved(state, lastError, provisionedAt, passwordRotatedAt, dbPassword,
                newQuotaBytes);
    }

    /** Whether the measurement the node last took is over the ceiling the panel set. */
    public boolean isOverQuota() {
        return usedBytes != null && usedBytes > quotaBytes;
    }

    /** How full it is, or -1 when the node has not measured it yet. */
    public int percentUsed() {
        if (usedBytes == null || quotaBytes <= 0) {
            return -1;
        }
        return (int) Math.min(usedBytes * 100 / quotaBytes, 100);
    }

    private ManagedDatabase moved(ManagedDatabaseState next, String error, Instant provisioned,
                                  Instant rotated, String password, long quota) {
        return new ManagedDatabase(id, projectId, databaseEngineId, name, dbUsername, password,
                dbCharset, dbCollation, quota, usedBytes, usedBytesMeasuredAt, next, error,
                provisioned, rotated, createdAt, updatedAt, version);
    }
}
