package lhqm.furimeo.wisper.audit;

/**
 * Writes the record of what happened.
 *
 * <p>Every state-changing action in the panel calls this, which makes it the most widely
 * depended-on seam in the codebase and the reason it is one method with one argument.
 * Implemented by {@code lhqm.furimeo.wisper.audit.AuditLogRecorder}.
 *
 * <h2>Two rules that are not negotiable</h2>
 *
 * <ol>
 * <li><strong>Writing the trail must not break the action.</strong> The implementation
 *     records in a {@code REQUIRES_NEW} transaction and swallows nothing quietly but
 *     rethrows nothing either: a failure to write an audit row is logged at
 *     {@code ERROR} and the action proceeds. A panel that refuses to stop a container
 *     because it could not write a log line has turned its audit trail into an outage.
 *     The trade is deliberate and it is the only place in this codebase where a write
 *     failure is tolerated.</li>
 * <li><strong>Refusals are recorded too.</strong> Call this on the denial path as well
 *     as the success path. {@link AuditOutcome#DENIED} exists because the interesting
 *     entry is usually the attempt that did not work.</li>
 * </ol>
 *
 * <p>The table is append-only. There is no update and no delete on this interface, and
 * none in the {@code audit} package either.
 */
public interface AuditTrail {

    /**
     * Appends one entry.
     *
     * <p>Never throws for anything the caller could have prevented: the entry was
     * already validated when it was constructed, and a database failure is logged
     * rather than propagated. Returns nothing, because there is nothing a caller could
     * usefully do with the id of an audit row.
     */
    void record(AuditEntry entry);
}
