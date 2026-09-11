package lhqm.furimeo.wisper.deploy;

import java.util.UUID;

import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceKind;

/**
 * The service a deployment is for, as the deployments screen needs it - and no more.
 *
 * <p>The {@code Service} record itself is never sent to a browser. It carries
 * {@code webhook_secret} and {@code repository_credential}, and both are encrypted
 * envelopes that the panel can decrypt: putting them in a page's props would hand a
 * customer's browser two ciphertexts it has no business holding, and would put them in
 * every HTML source view and every browser cache along the way. A projection is not
 * ceremony here; it is the boundary.
 *
 * @param webhookPath      where a Git provider posts for this service, relative to the
 *                         panel's own origin. Relative because the panel is behind a
 *                         tunnel and does not reliably know its public name; the page
 *                         prefixes it with {@code location.origin}, which is by definition
 *                         the address the customer reached it on
 * @param deploysFromGit   whether pressing deploy has a repository to clone
 * @param acceptsArchive   whether the upload-a-zip form is offered. Sites only: an app
 *                         deploys an image and has nothing to unpack
 */
public record DeploymentTarget(
        UUID serviceId,
        UUID projectId,
        String name,
        String slug,
        ServiceKind kind,
        String repositoryUrl,
        String repositoryBranch,
        boolean autoDeploy,
        int keepReleases,
        String webhookPath,
        boolean deploysFromGit,
        boolean acceptsArchive) {

    /** Reads a service down to what the screen may see. */
    public static DeploymentTarget of(Service service) {
        boolean site = service.kind() == ServiceKind.SITE;
        return new DeploymentTarget(
                service.id(),
                service.projectId(),
                service.name(),
                service.slug(),
                service.kind(),
                service.repositoryUrl(),
                service.repositoryBranch(),
                service.autoDeploy(),
                service.keepReleases(),
                "/webhooks/{provider}/" + service.id(),
                site && service.repositoryUrl() != null,
                site);
    }

    /** The branch a manual deploy uses when the customer does not name one. */
    public String defaultRef() {
        return repositoryBranch == null || repositoryBranch.isBlank() ? "main" : repositoryBranch;
    }
}
