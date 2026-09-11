package lhqm.furimeo.wisper.deploy;

import lhqm.furimeo.wisper.service.ServiceKind;

/**
 * Where the bytes being deployed come from. Mirrors the {@code deployment_source_known}
 * CHECK.
 *
 * <p>The choice is not the customer's: it follows from the service. A site is built, from
 * a repository or from a zip; an app in v1 runs an image somebody else built, because
 * {@code service_app_needs_image} requires an image and no {@code build_preset} in the
 * schema produces one. {@link #forKind} is that rule written once.
 */
public enum DeploymentSource {

    /** Cloned and built on the node that will serve it (design §11.4). Sites only. */
    GIT,

    /**
     * A zip the customer uploaded through the panel, for the project that is not in Git
     * (design §5.5). Sites only.
     *
     * <p>The archive reaches the node as a resumable chunked upload into its staging
     * root, the same path any other upload takes - a node can reach the panel's gRPC
     * endpoint and is not guaranteed to be able to reach anything else of the panel's.
     */
    ARCHIVE,

    /**
     * A container image, pinned to the digest the node resolved.
     *
     * <p>There is no build: publishing is a spec naming the image, and the node pulls and
     * converges. Apps only.
     */
    IMAGE;

    /** Whether a node has to run a build for this source. */
    public boolean needsBuild() {
        return this != IMAGE;
    }

    /**
     * The default source for a service of this kind.
     *
     * <p>A site with a repository builds from Git; a site without one can only be given
     * an archive, and the upload endpoint says so explicitly. An app always deploys its
     * image.
     */
    public static DeploymentSource forKind(ServiceKind kind, boolean hasRepository) {
        if (kind == ServiceKind.APP) {
            return IMAGE;
        }
        return hasRepository ? GIT : ARCHIVE;
    }

    /** The word the deployment list shows. */
    public String label() {
        return switch (this) {
            case GIT -> "Git";
            case ARCHIVE -> "Upload";
            case IMAGE -> "Image";
        };
    }
}
