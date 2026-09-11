package lhqm.furimeo.wisper.org;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * Turns a refusal into the two things it always has to become: a message on the screen
 * the customer is bounced back to, and a {@code DENIED} line in the audit trail.
 *
 * <p>Both halves matter and both are easy to half-do. A refusal with no message is a form
 * that appears to do nothing when submitted; a refusal with no audit line is the entry
 * missing from the trail on exactly the occasion somebody comes looking - "who kept trying
 * to invite people to this organization" is answered by the attempts, not by the
 * successes (panel-ports.md §2.4).
 *
 * <p>Every {@code catch} block in this package's controllers is one call to this. That is
 * deliberate: the alternative is the same six lines written twelve times, eleven of them
 * correctly.
 */
@Component
public class RecordRefusal {

    private final AuditTrail audit;

    public RecordRefusal(AuditTrail audit) {
        this.audit = audit;
    }

    /**
     * @param actor          who was refused
     * @param action         the dotted action they attempted
     * @param target         what they attempted it on
     * @param organizationId the tenant, or null for a platform-level attempt
     * @param refusal        the exception that stopped them
     * @param flash          where the message for the next page goes
     */
    public void of(AuditActor actor, String action, AuditTarget target, UUID organizationId,
                   RuntimeException refusal, RedirectAttributes flash) {
        String message = messageOf(refusal);
        if (refusal instanceof RequestRejected rejected && rejected.field() != null) {
            // Under the input that caused it, rather than as a banner over a form the
            // customer then has to re-read to find the problem.
            InertiaFlash.errors(flash, Map.of(rejected.field(), message));
        } else {
            InertiaFlash.failure(flash, message);
        }
        audit.record(AuditEntry.denied(actor, action, target, organizationId, message));
    }

    private static String messageOf(RuntimeException refusal) {
        if (refusal instanceof QuotaExceeded exceeded) {
            return exceeded.message();
        }
        String message = refusal.getMessage();
        return message == null || message.isBlank() ? "That is not allowed." : message;
    }
}
