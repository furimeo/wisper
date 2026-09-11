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
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Detaches a volume from a service.
 *
 * <p><strong>This removes the mount, not the bytes.</strong> The row goes and the next
 * spec no longer contains the mount, so the node stops attaching it; the directory under
 * {@code /var/lib/wisper/volumes/} stays until an operator purges it. That is deliberate
 * and it is the same rule everywhere in this system: a panel that deletes a customer's
 * disk because a row disappeared has one bad afternoon and no customers (schema.md §5).
 *
 * <p>The customer is told exactly that, because a delete button that means something
 * narrower than "delete" has to say so.
 *
 * <p>Requires the volume's name to be typed back. It is the same friction the project
 * delete uses and for a stronger reason: this is the only button in the panel that can
 * separate a customer from a database directory, and on a phone the confirm dialog is
 * three millimetres from the button that opened it.
 */
@Component
public class DeleteVolume {

    private final ServiceRepository services;
    private final VolumeRepository volumes;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public DeleteVolume(ServiceRepository services, VolumeRepository volumes,
                        PublishNodeSpec specs, AuditTrail audit) {
        this.services = services;
        this.volumes = volumes;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @param confirmation the volume's name, typed by the customer
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service or the volume is
     *                                                  not this organization's
     * @throws RequestRejected                          when the confirmation does not match
     */
    @Transactional
    public void delete(AuditActor actor, Membership membership, UUID serviceId, String name,
                       String confirmation) {
        membership.requireWrite("volume.delete");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        Volume volume = volumes.findByServiceIdAndName(serviceId, name == null ? "" : name.strip())
                .orElseThrow(() -> new NotFoundException(
                        "No volume called " + name + " on this service"));

        if (!volume.name().equals(confirmation == null ? null : confirmation.strip())) {
            throw new RequestRejected("confirmation",
                    "Type " + volume.name() + " to confirm that this volume should be detached.");
        }

        volumes.delete(volume);
        specs.forService(serviceId, "volume " + volume.name() + " removed from " + service.slug());

        audit.record(AuditEntry.succeeded(actor, "volume.delete",
                AuditTarget.of("volume", volume.id(), volume.name()),
                membership.organizationId(),
                "Detached from " + service.slug() + " at " + volume.mountPath()
                        + "; the data on the node is kept until it is purged"));
    }
}
