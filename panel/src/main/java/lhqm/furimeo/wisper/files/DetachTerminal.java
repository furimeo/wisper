package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Records the involuntary disconnect from a shell without ending it.
 *
 * <p>Called when the browser's socket closes without an explicit {@code Exit} frame: the
 * tab crashed, the phone moved networks, or the user navigated away by accident. The PTY
 * stays alive for {@code wisper.files.terminal-detach-window}; a customer who returns
 * within that window can reconnect to the same shell.
 *
 * <p>Audited as {@code terminal.detach} so the trail shows the gap between a disconnect
 * and a reconnect (or confirms the session ended through the reaper rather than the
 * customer).
 */
@Component
public class DetachTerminal {

    private final LiveTerminals live;
    private final AuditTrail audit;

    public DetachTerminal(LiveTerminals live, AuditTrail audit) {
        this.live = live;
        this.audit = audit;
    }

    /**
     * Detaches the browser from the shell without ending it.
     *
     * <p>Silent when the session is not found or is already finished: the reaper may have
     * claimed it in the millisecond between the socket closing and this call, which is the
     * normal race, not an error.
     */
    public void detach(TerminalAccess access, String sessionId) {
        live.find(access.serviceId(), sessionId).ifPresent(held -> {
            held.detachTemporarily();
            audit.record(AuditEntry.succeeded(access.actor(), "terminal.detach",
                    access.target(), access.organizationId(),
                    "connection dropped from shell session " + sessionId
                            + "; shell kept alive for reconnect"));
        });
    }
}
