package lhqm.furimeo.wisper.files;

import java.util.UUID;

/**
 * One directory the file manager may operate in, as the panel knows it.
 *
 * <p>The node's copy is {@code FileRoot} in {@code workload.proto}, built by
 * {@code placement.BuildFileRoots} and delivered in the spec. This is the panel-side view
 * of the same thing, derived from the same rows, and it exists so the browser can be shown
 * a list of roots and every request can be checked against the roots that belong to the
 * service in its URL - before a request is built, and without asking the node.
 *
 * <p>That check is the second half of path safety. {@link RelativePath} stops a path
 * leaving its root; this stops a root belonging to somebody else. Without it a member of
 * one organization could name another tenant's volume id and reach it through their own
 * service's URL, because the node only knows whether the root is in <em>its</em> spec.
 *
 * @param id        the id the node knows this directory by. A volume's id for a volume, the
 *                  service's id for a static site's releases tree - the same values
 *                  {@code BuildFileRoots} puts in the spec, so the two cannot drift
 * @param kind      volume or site
 * @param label     what the breadcrumb shows
 * @param writable  false for a site's releases: an edit made in place would be silently
 *                  reverted by the next deployment, which is worse than not being able to
 *                  make it
 * @param quotaBytes the ceiling the node applies, or zero when there is none
 */
public record FileRootRef(String id, FileRootKind kind, String label, boolean writable,
                          long quotaBytes) {

    /** What kind of directory a root is. Mirrors the customer-visible half of {@code FileRootKind}. */
    public enum FileRootKind {

        /** A workload volume: what the customer thinks of as their disk. */
        VOLUME,

        /** A static site's releases tree. Readable so a customer can see what a build produced. */
        SITE
    }

    public FileRootRef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A file root needs the id the node knows it by");
        }
        if (kind == null) {
            throw new IllegalArgumentException("A file root needs a kind");
        }
        label = label == null ? "" : label;
    }

    /** A volume of a service. */
    public static FileRootRef volume(UUID volumeId, String label, boolean readOnly,
                                     long quotaBytes) {
        return new FileRootRef(volumeId.toString(), FileRootKind.VOLUME, label, !readOnly,
                quotaBytes);
    }

    /**
     * A static site's releases tree.
     *
     * <p>Its id is the service id, because a site has exactly one releases tree and the
     * workload's own id is the only value that is stable, unique and already known to both
     * sides.
     */
    public static FileRootRef site(UUID serviceId, String label, long quotaBytes) {
        return new FileRootRef(serviceId.toString(), FileRootKind.SITE, label, true, quotaBytes);
    }
}
