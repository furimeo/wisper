package lhqm.furimeo.wisper.service;

import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * The webhook secret behind the deploy-on-push card.
 *
 * <h2>Why the value arrives as a flash attribute</h2>
 *
 * <p>Not a prop: a prop is re-sent on every partial reload of that page, so a secret
 * revealed once would be sitting in the props of a screen the customer leaves open on a
 * shared monitor. Not a query parameter either: that survives the back button, the
 * browser's history and whatever proxy sits in front of the panel. A flash attribute is
 * read once by the render that follows the redirect and is then gone.
 *
 * <h2>Why both verbs are POST</h2>
 *
 * <p>Revealing is a read, but a read with a side effect - it writes an audit record, and
 * it is the record that makes revealing safe to offer at all. A GET would be prefetched
 * by a browser, retried by a proxy and followed by a link checker, and the audit trail
 * would fill with reveals nobody performed.
 *
 * <p>Both redirect to the deployments page, which is where the webhook card lives.
 */
@Controller
public class WebhookSecretController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final RevealWebhookSecret revealWebhookSecret;
    private final RotateWebhookSecret rotateWebhookSecret;
    private final RecordRefusal refusals;

    public WebhookSecretController(ResolveCurrentAccount currentAccount,
                                   ResolveMembership memberships,
                                   RevealWebhookSecret revealWebhookSecret,
                                   RotateWebhookSecret rotateWebhookSecret,
                                   RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.revealWebhookSecret = revealWebhookSecret;
        this.rotateWebhookSecret = rotateWebhookSecret;
        this.refusals = refusals;
    }

    @PostMapping("/services/{serviceId}/webhook/reveal")
    public String reveal(@PathVariable UUID serviceId,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            flash.addFlashAttribute("webhookSecret",
                    revealWebhookSecret.of(actor, membership, serviceId));
            InertiaFlash.success(flash, "Paste this into your provider's webhook settings. "
                    + "It stays valid until you rotate it.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "service.webhook_secret.reveal", target(serviceId),
                    membership.organizationId(), refused, flash);
        }
        return back(serviceId);
    }

    @PostMapping("/services/{serviceId}/webhook/rotate")
    public String rotate(@PathVariable UUID serviceId,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            flash.addFlashAttribute("webhookSecret",
                    rotateWebhookSecret.of(actor, membership, serviceId));
            InertiaFlash.success(flash, "New secret. Deliveries signed with the old one are "
                    + "rejected from now on, so update every provider pointed at this service.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "service.webhook_secret.rotate", target(serviceId),
                    membership.organizationId(), refused, flash);
        }
        return back(serviceId);
    }

    private static AuditTarget target(UUID serviceId) {
        return AuditTarget.of("service", serviceId, null);
    }

    private static String back(UUID serviceId) {
        return "redirect:/services/" + serviceId + "/deployments";
    }
}
