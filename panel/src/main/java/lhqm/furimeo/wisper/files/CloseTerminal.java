package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Ends a shell.
 *
 * <p>Closing the session ends the stream to the node, which kills the PTY. That is what the
 * browser going away has to trigger, so it is called when the customer ends the session, when
 * their socket closes - a tab closed, a phone that left the network - and by the reaper when
 * the shell exits on its own. It is idempotent, because all three can happen at once.
 *
 * <p>Audited as {@code terminal.close} with the same session id the open used, which is what
 * turns two entries into one session in the trail: who had a shell, in which container, for
 * how long.
 */
@Component
public class CloseTerminal {

    private final LiveTerminals live;
    private final AuditTrail audit;

    public CloseTerminal(LiveTerminals live, AuditTrail audit) {
        this.live = live;
        this.audit = audit;
    }

    /**
     * Ends the session if this panel holds it.
     *
     * <p>Silent for a session that is already gone: the reaper closing a shell that exited a
     * second before the customer pressed the button is the normal race, not an error either
     * of them needs to hear about.
     */
    public void close(TerminalAccess access, String sessionId) {
        if (live.find(access.serviceId(), sessionId).isEmpty()) {
            return;
        }
        live.release(sessionId);
        audit.record(AuditEntry.succeeded(access.actor(), "terminal.close", access.target(),
                access.organizationId(), "closed shell session " + sessionId));
    }
}
