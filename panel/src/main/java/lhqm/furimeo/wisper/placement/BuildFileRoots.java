package lhqm.furimeo.wisper.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.proto.v1.FileRoot;
import lhqm.furimeo.wisper.proto.v1.FileRootKind;

/**
 * The directories a node will let the file manager touch.
 *
 * <p>There is no SSH, no SFTP and no WebDAV, so this list is the <em>entire</em> surface a
 * customer has on a node's filesystem (design §8.2). Everything the browser asks for names
 * a root by id and a path relative to it: the panel never learns an absolute path and never
 * sends one, and the node resolves the root itself and refuses anything that climbs out of
 * it. Path traversal is the largest attack surface in v1, and this shape is what makes it
 * checkable in one place instead of at every call site.
 *
 * <p>Three kinds of root, and the writable flag is different for each for a reason:
 *
 * <ul>
 * <li>A <strong>volume</strong> is what the customer thinks of as their disk. Writable
 *     unless the volume itself is read-only.</li>
 * <li>A <strong>site</strong> exposes the releases tree so a customer can see what a build
 *     actually produced. Never writable: an edit made in place would be silently reverted
 *     by the next deployment, which is worse than not being able to make it.</li>
 * <li><strong>Upload staging</strong> is where {@code deploy} pushes an archive before
 *     asking for a build. One per node, not shown to customers, and its id has to match
 *     {@code wisper.deploy.staging-root-id} - the two settings share a default and
 *     {@link PlacementSettings} says so.</li>
 * </ul>
 */
@Component
public class BuildFileRoots {

    private final PlacementSettings settings;

    public BuildFileRoots(PlacementSettings settings) {
        this.settings = settings;
    }

    /**
     * Every root this node should expose, staging first and then the customers' own.
     *
     * <p>The staging root is present even on a node holding nothing. A spec is published
     * when a service is placed and the archive is pushed immediately afterwards, so a
     * staging directory that only appeared once there was something to build would not be
     * there when the first build needs it.
     */
    public List<FileRoot> from(List<PlacedService> placed,
                               Map<UUID, List<MountedVolume>> volumes) {
        List<FileRoot> roots = new ArrayList<>();
        roots.add(FileRoot.newBuilder()
                .setId(settings.uploadStagingRootId())
                .setKind(FileRootKind.FILE_ROOT_KIND_UPLOAD_STAGING)
                .setLabel("Upload staging")
                .setWritable(true)
                .build());

        for (PlacedService entry : placed) {
            for (MountedVolume volume : volumes.getOrDefault(entry.service().id(), List.of())) {
                roots.add(FileRoot.newBuilder()
                        .setId(volume.rootId())
                        .setKind(FileRootKind.FILE_ROOT_KIND_VOLUME)
                        .setWorkloadId(entry.workloadId())
                        .setVolumeId(volume.rootId())
                        .setLabel(entry.service().slug() + " / " + volume.name())
                        .setWritable(!volume.readOnly())
                        .setQuotaBytes(volume.quotaBytes())
                        .build());
            }
            if (entry.isSite()) {
                roots.add(FileRoot.newBuilder()
                        // The service id: a site has exactly one releases tree, so the
                        // workload's own id is the only value that is stable, unique and
                        // already known to both sides.
                        .setId(entry.workloadId())
                        .setKind(FileRootKind.FILE_ROOT_KIND_SITE)
                        .setWorkloadId(entry.workloadId())
                        .setLabel(entry.service().slug() + " releases")
                        .setWritable(false)
                        .setQuotaBytes(entry.service().diskBytes())
                        .build());
            }
        }
        return List.copyOf(roots);
    }
}
