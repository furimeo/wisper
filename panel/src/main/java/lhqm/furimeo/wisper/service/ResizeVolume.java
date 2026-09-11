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
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Changes a volume's quota.
 *
 * <p>Growing charges the difference and nothing more. The existing size is already part of
 * the organization's usage, so asking the guard for the new total would bill the customer
 * twice for the gigabytes they already have and refuse a resize they can afford.
 *
 * <p>Shrinking is free of charge and is checked against reality instead: a quota below
 * what is already stored does not delete anything, it makes the next write fail with a
 * disk-full error inside the customer's container, several layers away from the form that
 * caused it. If the node has measured the volume, that is refused here with the number.
 * If the node has never reported - a volume on a node that has not connected yet - there
 * is nothing to compare against, and the panel says so rather than guessing.
 */
@Component
public class ResizeVolume {

    private static final long MIN_SIZE_BYTES = 1_048_576L;

    private static final long MAX_SIZE_BYTES = 4_398_046_511_104L;

    private final ServiceRepository services;
    private final VolumeRepository volumes;
    private final QuotaGuard quotas;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public ResizeVolume(ServiceRepository services, VolumeRepository volumes, QuotaGuard quotas,
                        PublishNodeSpec specs, AuditTrail audit) {
        this.services = services;
        this.volumes = volumes;
        this.quotas = quotas;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service or the volume is
     *                                                  not this organization's
     * @throws RequestRejected                          for a size outside the range, or one
     *                                                  below what the node says is stored
     * @throws lhqm.furimeo.wisper.org.QuotaExceeded    when the increase does not fit the
     *                                                  plan
     */
    @Transactional
    public Volume resize(AuditActor actor, Membership membership, UUID serviceId, String name,
                         long sizeBytes) {
        membership.requireWrite("volume.resize");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        Volume volume = volumes.findByServiceIdAndName(serviceId, name == null ? "" : name.strip())
                .orElseThrow(() -> new NotFoundException(
                        "No volume called " + name + " on this service"));

        if (sizeBytes < MIN_SIZE_BYTES || sizeBytes > MAX_SIZE_BYTES) {
            throw new RequestRejected("sizeMebibytes", "A volume is between 1 MiB and 4 TiB.");
        }
        if (sizeBytes == volume.sizeBytes()) {
            return volume;
        }
        if (volume.usedBytes() != null && sizeBytes < volume.usedBytes()) {
            throw new RequestRejected("sizeMebibytes",
                    "There are already " + volume.usedBytes() + " bytes on this volume, so a "
                            + "quota of " + sizeBytes + " would leave it over its limit. Delete "
                            + "some files first.");
        }

        long growth = sizeBytes - volume.sizeBytes();
        if (growth > 0) {
            quotas.require(membership.organizationId(), QuotaResource.VOLUME_BYTES, growth);
        }

        Volume resized = volumes.save(volume.resizedTo(sizeBytes));
        specs.forService(serviceId, "volume " + volume.name() + " resized on " + service.slug());

        audit.record(AuditEntry.succeeded(actor, "volume.resize",
                AuditTarget.of("volume", resized.id(), resized.name()),
                membership.organizationId(),
                "From " + volume.sizeBytes() + " to " + sizeBytes + " bytes on " + service.slug()));
        return resized;
    }
}
