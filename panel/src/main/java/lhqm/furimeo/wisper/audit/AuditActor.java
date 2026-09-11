package lhqm.furimeo.wisper.audit;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who performed an action, and from where.
 *
 * <p>The identity half is a kind plus at most one id, matching the
 * {@code audit_log_actor_consistent} CHECK. The provenance half - address, user agent,
 * request id - is what turns a list of actions into something an incident can be
 * reconstructed from, and it is filled from the request rather than by each caller, so
 * it cannot be forgotten in the one place it mattered.
 *
 * <p>{@code label} is required and survives the actor being deleted: the foreign keys on
 * {@code audit_log} are {@code SET NULL} precisely so the trail outlives the account, and
 * a trail of null actors is not a trail. Use the email for a person, the token name for
 * a token, the node name for a node.
 *
 * @param kind          which of the four actors this is
 * @param accountId     the signing-in account; null unless {@code kind} is
 *                      {@code ACCOUNT} or {@code API_TOKEN}
 * @param apiTokenId    the token used; null unless {@code kind} is {@code API_TOKEN}
 * @param nodeId        the reporting node; null unless {@code kind} is {@code NODE}
 * @param label         human-readable identity, required and non-blank
 * @param remoteAddress the caller's address as the panel saw it after
 *                      {@code forward-headers-strategy}, or null for {@code SYSTEM}
 * @param userAgent     the browser or client string, or null
 * @param requestId     correlation id, when the deployment in front sets one
 */
public record AuditActor(
        AuditActorKind kind,
        UUID accountId,
        UUID apiTokenId,
        UUID nodeId,
        String label,
        String remoteAddress,
        String userAgent,
        String requestId) {

    /** Header a tunnel or reverse proxy sets to correlate one request end to end. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    public AuditActor {
        if (kind == null) {
            throw new IllegalArgumentException("An audit actor needs a kind");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("An audit actor needs a label; the ids are nulled "
                    + "when the actor is deleted and the label is what remains");
        }
        // The same rule the database enforces, checked here so the failure names the
        // mistake instead of arriving as a constraint violation three frames later.
        switch (kind) {
            case ACCOUNT -> require(apiTokenId == null && nodeId == null,
                    "An ACCOUNT actor carries no token and no node");
            case API_TOKEN -> require(nodeId == null,
                    "An API_TOKEN actor carries no node");
            case NODE -> require(accountId == null && apiTokenId == null,
                    "A NODE actor carries no account and no token");
            case SYSTEM -> require(accountId == null && apiTokenId == null && nodeId == null,
                    "A SYSTEM actor carries no ids at all");
        }
    }

    /** A signed-in person, with the request they were making. */
    public static AuditActor account(UUID accountId, String email, HttpServletRequest request) {
        return new AuditActor(AuditActorKind.ACCOUNT, accountId, null, null, email,
                request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader(REQUEST_ID_HEADER));
    }

    /** A program calling {@code /api/v1/**} with a scoped token. */
    public static AuditActor apiToken(UUID accountId, UUID apiTokenId, String tokenName,
                                      HttpServletRequest request) {
        return new AuditActor(AuditActorKind.API_TOKEN, accountId, apiTokenId, null, tokenName,
                request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader(REQUEST_ID_HEADER));
    }

    /**
     * A node reporting something that changed panel state.
     *
     * <p>There is no request: the node is at the other end of a gRPC stream, so the
     * address is the one the stream was opened from and the {@code grpc} package passes
     * it in.
     */
    public static AuditActor node(UUID nodeId, String nodeName, String remoteAddress) {
        return new AuditActor(AuditActorKind.NODE, null, null, nodeId, nodeName,
                remoteAddress, null, null);
    }

    /**
     * The panel acting on its own: a scheduled job, a sweep, a retention pass.
     *
     * <p>{@code label} is the job that did it - {@code "backup-prune"} - because "system"
     * on its own answers none of the questions the entry exists for.
     */
    public static AuditActor system(String label) {
        return new AuditActor(AuditActorKind.SYSTEM, null, null, null, label, null, null, null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
