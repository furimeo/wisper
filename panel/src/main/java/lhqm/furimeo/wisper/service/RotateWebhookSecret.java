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
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Replaces a service's webhook secret, invalidating the old one.
 *
 * <p>This is the answer to a leak. The moment it returns, every delivery signed with the
 * previous secret fails verification, so the customer's next job is to paste the new
 * value into each provider pointed at this service. That gap is deliberate and cannot be
 * closed by accepting both secrets for a while: a compromised secret that keeps working
 * for a grace period is a compromised secret that keeps working.
 *
 * <p>Nothing else about the service moves - not its desired state, not the deployment
 * that happens to be running. Rotation is done during incidents, and an operation that
 * also restarted the customer's application would be one nobody dares to use.
 *
 * <p>Administration, not write, and audited, for the same reasons as
 * {@link RevealWebhookSecret}.
 */
@Component
public class RotateWebhookSecret {

    private final ServiceRepository services;
    private final SecretCipher cipher;
    private final AuditTrail audit;

    public RotateWebhookSecret(ServiceRepository services, SecretCipher cipher, AuditTrail audit) {
        this.services = services;
        this.cipher = cipher;
        this.audit = audit;
    }

    /**
     * @return the new plaintext secret, so the page that asked can show it once
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for anyone below an administrator
     * @throws NotFoundException if the service is not in this membership's organization
     */
    @Transactional
    public String of(AuditActor actor, Membership membership, UUID serviceId) {
        membership.requireAdministration("service.webhook_secret.rotate");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        String secret = WebhookSecret.generate();
        services.save(service.rotatedTo(cipher.encrypt(secret)));

        audit.record(AuditEntry.succeeded(actor, "service.webhook_secret.rotate",
                AuditTarget.of("service", service.id(), service.name()),
                membership.organizationId(),
                "Webhook secret rotated for " + service.slug()
                        + "; deliveries signed with the old one now fail"));
        return secret;
    }
}
