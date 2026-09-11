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
import lhqm.furimeo.wisper.project.Slug;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Gives a service a disk.
 *
 * <p>Apps only. A static site has no container, so there is nowhere for a volume to
 * appear: {@code Mount} in the spec names a path inside a workload, and a site is a
 * directory Caddy reads. A customer who wants files next to their site wants the file
 * manager, which serves the release tree directly.
 *
 * <p>This is also the moment a service stops being movable. The bytes are on one machine
 * from here on, the placement is pinned to it, and relocating the service becomes a
 * deliberate migration rather than something the scheduler does quietly (design §7.8).
 * That is worth knowing before the first volume, not after the first hundred gigabytes,
 * and it is why the panel says so on this form.
 *
 * <p>{@code VOLUME_BYTES} is charged for the space promised, not the space used. The node
 * enforces {@code size_bytes} as an XFS project quota whether or not the customer fills
 * it, so the platform has committed the disk the moment the row exists.
 */
@Component
public class CreateVolume {

    /** Smaller than this is a typo: a filesystem quota below 1 MiB fits nothing. */
    private static final long MIN_SIZE_BYTES = 1_048_576L;

    /** 4 TiB. Beyond this the plan is not what needs changing. */
    private static final long MAX_SIZE_BYTES = 4_398_046_511_104L;

    private final ServiceRepository services;
    private final VolumeRepository volumes;
    private final QuotaGuard quotas;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public CreateVolume(ServiceRepository services, VolumeRepository volumes, QuotaGuard quotas,
                        PublishNodeSpec specs, AuditTrail audit) {
        this.services = services;
        this.volumes = volumes;
        this.quotas = quotas;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @param name          lower-case, unique within the service; it names the directory
     *                      in the file manager, not on disk
     * @param mountPath     where it appears inside the container
     * @param sizeBytes     the quota to apply
     * @param readOnly      mount it read-only, for a volume a sibling service writes
     * @param backupEnabled include it in scheduled snapshots
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service is not this
     *                                                  organization's
     * @throws RequestRejected                          for a site, a bad name or path, a
     *                                                  collision, or an unusable size
     * @throws lhqm.furimeo.wisper.org.QuotaExceeded    at the plan's storage ceiling
     */
    @Transactional
    public Volume create(AuditActor actor, Membership membership, UUID serviceId, String name,
                         String mountPath, long sizeBytes, boolean readOnly,
                         boolean backupEnabled) {
        membership.requireWrite("volume.create");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        if (service.isSite()) {
            throw new RequestRejected(null,
                    "A static site has no container to mount a volume into. Use the file manager "
                            + "to work with the files it publishes.");
        }

        String volumeName = Slug.normalise(name == null || name.isBlank() ? "data" : name);
        if (!Slug.isShortValid(volumeName)) {
            throw new RequestRejected("name", "That name will not work. " + Slug.rule(1));
        }
        String path = MountPath.require(mountPath);

        if (sizeBytes < MIN_SIZE_BYTES || sizeBytes > MAX_SIZE_BYTES) {
            throw new RequestRejected("sizeMebibytes",
                    "A volume is between 1 MiB and 4 TiB.");
        }
        if (volumes.existsByServiceIdAndName(serviceId, volumeName)) {
            throw new RequestRejected("name",
                    "This service already has a volume called " + volumeName + ".");
        }
        if (volumes.existsByServiceIdAndMountPath(serviceId, path)) {
            throw new RequestRejected("mountPath",
                    "Something is already mounted at " + path + " on this service.");
        }

        quotas.require(membership.organizationId(), QuotaResource.VOLUME_BYTES, sizeBytes);

        Volume volume = volumes.save(Volume.of(UUID.randomUUID(), serviceId, volumeName, path,
                sizeBytes, readOnly, backupEnabled));

        specs.forService(serviceId, "volume " + volumeName + " added to " + service.slug());

        audit.record(AuditEntry.succeeded(actor, "volume.create",
                AuditTarget.of("volume", volume.id(), volume.name()),
                membership.organizationId(),
                "Created on " + service.slug() + " at " + path + ", " + sizeBytes + " bytes"));
        return volume;
    }
}
