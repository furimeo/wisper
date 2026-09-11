package lhqm.furimeo.wisper.database;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the customer's database list, and the props of its detail page.
 *
 * <p>Everything a person needs to understand the database and <strong>nothing that would
 * let them connect to it</strong>. There is no password here and no URI: those come from
 * {@link ConnectionString}, which is built only when somebody presses "show connection
 * details" and only after that press has been written to the audit trail. A credential
 * that is on the page anyway is a credential in a browser cache and a screenshot.
 *
 * <p>Built by {@link ListDatabases} straight from a join, because a row spans four tables
 * and loading four aggregates per row to assemble one line of a list is four round trips
 * a phone waits for.
 *
 * @param usedBytes     what the node last measured. Null until it has measured once, which
 *                      is not the same as zero, and the screen says "not measured yet"
 *                      rather than drawing an empty bar
 * @param percentUsed   -1 for the same reason
 * @param overQuota     computed from the last measurement against the current ceiling, so
 *                      raising the quota clears the warning on the next page load rather
 *                      than on the next status batch
 * @param nodeReachable whether the panel currently has a control stream to the node. False
 *                      greys out rotate and delete; it says nothing about whether the
 *                      database is serving, because a node behind a dropped tunnel keeps
 *                      running everything it was given
 */
public record ManagedDatabaseView(
        UUID id,
        UUID organizationId,
        String organizationName,
        UUID projectId,
        String projectName,
        String name,
        String username,
        EngineKind engine,
        String engineLabel,
        String engineVersion,
        String host,
        int port,
        boolean dedicated,
        ManagedDatabaseState state,
        long quotaBytes,
        Long usedBytes,
        Instant measuredAt,
        int percentUsed,
        boolean overQuota,
        Instant provisionedAt,
        Instant passwordRotatedAt,
        String lastError,
        UUID nodeId,
        String nodeName,
        boolean nodeReachable) {

    /** Whether the screen should offer rotate, drop and the connection details. */
    public boolean isActionable() {
        return state.isUsable() && nodeReachable;
    }

    /** Whether the platform still owes this row work, so the page should keep polling. */
    public boolean isInFlight() {
        return state.isInFlight();
    }

    /** The address without a credential, for a list row that wants to show something. */
    public String address() {
        return host + ":" + port + "/" + name;
    }
}
