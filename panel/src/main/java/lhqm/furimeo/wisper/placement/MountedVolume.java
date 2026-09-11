package lhqm.furimeo.wisper.placement;

import java.util.UUID;

/**
 * A volume as the spec needs it: an id, where it appears inside the container, and how big
 * it is allowed to get.
 *
 * <p>Deliberately narrower than {@code service.Volume}, which also carries everything the
 * node has reported back about the directory. None of that belongs in a spec - a spec is
 * what the node must make true, and telling a machine how full it measured its own disk to
 * be last Tuesday is at best noise and at worst a value it starts believing.
 *
 * <p>There is no path here either. The panel sends {@link #id} and the node resolves
 * {@code /var/lib/wisper/volumes/<service-id>/<volume-id>} itself. A path that arrives over
 * the network and reaches a filesystem call is the bug class this platform is built to
 * avoid, and the layout is fixed on ids so that renaming a service never moves a directory
 * (design §11.1).
 */
public record MountedVolume(
        UUID id,
        UUID serviceId,
        String name,
        String mountPath,
        long quotaBytes,
        boolean readOnly) {

    /** The id the file manager and the spec both know this directory by. */
    public String rootId() {
        return id.toString();
    }
}
