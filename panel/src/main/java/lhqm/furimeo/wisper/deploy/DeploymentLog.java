package lhqm.furimeo.wisper.deploy;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

/**
 * One line of build output. Maps the {@code deployment_log} table.
 *
 * <p>Rows rather than one growing text column: the deployment page streams this over SSE
 * and a reconnecting browser resumes with "everything after line N", which a single
 * column cannot answer. Rewriting a whole column per line is also how the predecessor's
 * build log became the slowest write in its database.
 *
 * <p>Append-only. Nothing updates a row once it is written, and there is no method here
 * that would. It still carries {@code version}, because that is what tells Spring Data
 * JDBC an application-assigned uuid is a new row rather than an update of one that does
 * not exist - schema.md §1 has no exceptions to that rule, including on append-only
 * tables.
 *
 * <p>There is no {@code @CreatedDate} on {@code loggedAt}: the timestamp that matters is
 * the node's, taken when the byte was written, not the panel's, taken when the chunk
 * happened to arrive after a reconnect.
 *
 * @param sequence line number within the deployment, from 1. Both the ordering and the
 *                 SSE resume cursor; {@code deployment_log_cursor_key} makes a
 *                 redelivered chunk a rejected duplicate instead of a doubled line
 */
public record DeploymentLog(
        @Id UUID id,
        UUID deploymentId,
        long sequence,
        DeploymentLogStream stream,
        String message,
        Instant loggedAt,
        @Version Long version) {

    /**
     * The longest line stored.
     *
     * <p>A build tool that prints a megabyte-wide progress bar with no newline in it
     * would otherwise put a megabyte in one row and send it to a phone. Cut lines are
     * marked, so the customer knows the tool said more than this.
     */
    public static final int MESSAGE_LIMIT = 8_192;

    private static final String CUT = "… [line truncated]";

    /** A line the build printed. */
    public static DeploymentLog of(UUID id, UUID deploymentId, long sequence,
                                   DeploymentLogStream stream, String message, Instant at) {
        return new DeploymentLog(id, deploymentId, sequence, stream, cut(message), at, null);
    }

    /** A line the panel wrote: which node, why it failed, how much output was dropped. */
    public static DeploymentLog system(UUID id, UUID deploymentId, long sequence,
                                       String message, Instant at) {
        return of(id, deploymentId, sequence, DeploymentLogStream.SYSTEM, message, at);
    }

    /** Whether this line came from the platform rather than from the build. */
    public boolean isFromPanel() {
        return stream == DeploymentLogStream.SYSTEM;
    }

    private static String cut(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= MESSAGE_LIMIT
                ? message
                : message.substring(0, MESSAGE_LIMIT - CUT.length()) + CUT;
    }
}
