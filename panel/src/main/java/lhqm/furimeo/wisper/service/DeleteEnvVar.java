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
 * Removes one environment variable from a service.
 *
 * <p>Deleted by name rather than by row id. The name is what the customer sees, what the
 * form shows and what the variable actually is; an id in a hidden field is one stale page
 * away from deleting the row that happens to sit in the same position now.
 *
 * <p>The variable disappears from the next spec, and the node applies that by recreating
 * the container without it - an environment is fixed when a process starts, so a running
 * workload keeps the old value until it restarts. The panel says so on the page rather
 * than pretending the change is instant.
 */
@Component
public class DeleteEnvVar {

    private final ServiceRepository services;
    private final EnvVarRepository envVars;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public DeleteEnvVar(ServiceRepository services, EnvVarRepository envVars,
                        PublishNodeSpec specs, AuditTrail audit) {
        this.services = services;
        this.envVars = envVars;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service is not this
     *                                                  organization's, or has no variable
     *                                                  by that name
     */
    @Transactional
    public void delete(AuditActor actor, Membership membership, UUID serviceId, String name) {
        membership.requireWrite("env_var.delete");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        EnvVar variable = envVars.findByServiceIdAndName(serviceId, name == null ? "" : name.strip())
                .orElseThrow(() -> new NotFoundException(
                        "No environment variable called " + name + " on this service"));

        envVars.delete(variable);
        specs.forService(serviceId, "environment of " + service.slug() + " changed");

        audit.record(AuditEntry.succeeded(actor, "env_var.delete",
                AuditTarget.of("service", service.id(), service.name()),
                membership.organizationId(), "Removed " + variable.name()));
    }
}
