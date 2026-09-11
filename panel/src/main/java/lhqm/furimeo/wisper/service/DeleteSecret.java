package lhqm.furimeo.wisper.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Removes a secret from a service.
 *
 * <p>By name, like {@link DeleteEnvVar} and for the same reason: the name is what the
 * customer is looking at, and a row id in a hidden field is one stale page away from
 * deleting a different secret than the one on screen. For a value nobody can read back,
 * deleting the wrong one is not recoverable by looking.
 *
 * <p>The row goes; the value goes with it. There is no soft delete and no archive of
 * secrets, because a copy of a credential kept "just in case" is a copy of a credential.
 */
@Component
public class DeleteSecret {

    private final ServiceRepository services;
    private final SecretRepository secrets;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public DeleteSecret(ServiceRepository services, SecretRepository secrets,
                        PublishNodeSpec specs, AuditTrail audit) {
        this.services = services;
        this.secrets = secrets;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service is not this
     *                                                  organization's, or has no secret by
     *                                                  that name
     */
    @Transactional
    public void delete(AuditActor actor, Membership membership, UUID serviceId, String name) {
        membership.requireWrite("secret.delete");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        Secret secret = secrets.findByServiceIdAndName(serviceId, name == null ? "" : name.strip())
                .orElseThrow(() -> new NotFoundException(
                        "No secret called " + name + " on this service"));

        secrets.delete(secret);
        specs.forService(serviceId, "secrets of " + service.slug() + " changed");

        audit.record(AuditEntry.succeeded(actor, "secret.delete",
                AuditTarget.of("service", service.id(), service.name()),
                membership.organizationId(), "Removed " + secret.name()));
    }
}
