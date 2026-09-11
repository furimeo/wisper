package lhqm.furimeo.wisper.database;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code /admin/databases}: an engine container, what it is for, and what the
 * node last said about it.
 *
 * <p>Admin-facing. Customers never see a row from {@code database_engine}; they see the
 * connection string built from one. The administrative password is not here either -
 * nothing in the panel displays it, because nothing needs to: the panel uses it to create
 * customer databases and that use goes over the spec, not through a screen.
 *
 * <p>Built by {@link ListDatabaseEngines} straight from a join, for the same reason
 * {@link ManagedDatabaseView} is: a row spans three tables and a count.
 *
 * @param reportedState what the node said, or null before it has said anything. The two
 *                      are different answers and the screen shows them differently: a
 *                      brand new engine has not been reported on rather than being down
 * @param databaseCount how many customer databases the engine holds, which is both the
 *                      load figure placement uses and the reason it cannot be deleted
 * @param owner         the organization's name for a dedicated instance, empty for a
 *                      shared one
 */
public record DatabaseEngineView(
        UUID id,
        EngineKind engine,
        String engineLabel,
        String engineVersion,
        String image,
        EngineMode mode,
        UUID organizationId,
        String owner,
        UUID nodeId,
        String nodeName,
        boolean nodeReachable,
        String host,
        int port,
        String dataPath,
        EngineDesiredState desiredState,
        String reportedState,
        Instant reportedAt,
        Long diskBytesUsed,
        String lastError,
        long databaseCount,
        Instant createdAt) {

    /** Whether what the operator asked for and what the node reports are the same thing. */
    public boolean isConverged() {
        return switch (desiredState) {
            case RUNNING -> "RUNNING".equals(reportedState);
            case STOPPED -> "STOPPED".equals(reportedState);
        };
    }

    /**
     * Whether this engine can be deleted at all.
     *
     * <p>{@code managed_database.database_engine_id} is {@code ON DELETE RESTRICT}: an
     * engine still holding customer databases is not deletable, and the screen says so
     * instead of offering a button that fails.
     */
    public boolean isRemovable() {
        return databaseCount == 0;
    }
}
