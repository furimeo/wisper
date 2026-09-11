package lhqm.furimeo.wisper.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Shows an administrator the webhook secret for one of their services.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A service is created with a webhook secret and {@code GitWebhookController} verifies
 * every delivery's signature against it. Without a way to read it back, the customer can
 * never finish the other half of the setup - pasting it into GitHub or GitLab - so
 * {@code POST /webhooks/**} would reject everything it was ever sent, and deploy-on-push
 * would be a feature that exists in the schema and nowhere else.
 *
 * <p>This is a real read of a stored secret, not a one-time reveal like an API token. A
 * shared secret has to be readable by both parties by definition: hashing it would make
 * the HMAC unverifiable, and showing it only at creation would mean a customer
 * reinstalling a webhook two months later has to rotate - breaking every other provider
 * pointed at the same service - to recover from a lost clipboard.
 *
 * <h2>What keeps it honest</h2>
 *
 * <p>Administration, not write. A developer who may deploy does not need the credential
 * that lets an unauthenticated request queue a deployment.
 *
 * <p>Every reveal is recorded. The audit line is the only durable trace that this value
 * left the database, and "who has seen this" is the first question asked after a
 * deployment nobody admits to triggering.
 *
 * <p>The secret is returned and nothing else. It is never logged and never becomes a
 * model attribute, because a prop survives a reload and a reload survives a shared
 * screen; the controller puts it in a flash attribute for exactly one render.
 */
@Component
public class RevealWebhookSecret {

    private final ServiceRepository services;
    private final SecretCipher cipher;
    private final AuditTrail audit;

    public RevealWebhookSecret(ServiceRepository services, SecretCipher cipher, AuditTrail audit) {
        this.services = services;
        this.cipher = cipher;
        this.audit = audit;
    }

    /**
     * @return the plaintext secret
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for anyone below an administrator
     * @throws NotFoundException if the service is not in this membership's organization,
     *                           which is also the answer for a service that exists
     *                           somewhere else
     * @throws RequestRejected   if the row carries no secret, which a service created
     *                           before this column was populated can still do
     */
    @Transactional(readOnly = true)
    public String of(AuditActor actor, Membership membership, UUID serviceId) {
        membership.requireAdministration("service.webhook_secret.reveal");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        if (service.webhookSecret() == null || service.webhookSecret().isBlank()) {
            throw new RequestRejected(null, "This service has no webhook secret yet. "
                    + "Rotate it to generate one.");
        }

        String secret = cipher.decrypt(service.webhookSecret());
        audit.record(AuditEntry.succeeded(actor, "service.webhook_secret.reveal",
                AuditTarget.of("service", service.id(), service.name()),
                membership.organizationId(),
                "Webhook secret shown for " + service.slug()));
        return secret;
    }
}
