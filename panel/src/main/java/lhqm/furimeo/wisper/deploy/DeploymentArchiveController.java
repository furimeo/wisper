package lhqm.furimeo.wisper.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceKind;
import lhqm.furimeo.wisper.service.ServiceRepository;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Deploy a site from a zip: {@code POST /services/{serviceId}/deployments/upload}.
 *
 * <p>The path for the project that is not in Git (design §5.5), and the reason a customer
 * with a folder of HTML never has to learn a version control system to use this platform.
 *
 * <p>Its own controller because it is the one write in this package that parses a
 * multipart body and touches the filesystem, and because it is the only one whose failure
 * path has something to clean up. A stored archive whose deployment was refused is a file
 * nobody will ever come back for, so every exit that is not a queued deployment deletes
 * it on the way out.
 *
 * <p>One part, not resumable chunks. The file manager's upload is chunked because a phone
 * on 4G loses signal mid-upload (design §8.2); a deploy archive is usually a few megabytes
 * from a laptop, and {@code spring.servlet.multipart.max-file-size} is set to the ceiling
 * this accepts. The resumable path exists on the hop that is actually unreliable - panel
 * to node, over a tunnel - and that is {@link PushDeploymentArchive}.
 */
@Controller
public class DeploymentArchiveController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ServiceRepository services;
    private final StoreDeploymentArchive archives;
    private final StartDeployment startDeployment;
    private final RecordRefusal refusals;

    public DeploymentArchiveController(ResolveCurrentAccount currentAccount,
                                       ResolveMembership memberships, ServiceRepository services,
                                       StoreDeploymentArchive archives,
                                       StartDeployment startDeployment, RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.services = services;
        this.archives = archives;
        this.startDeployment = startDeployment;
        this.refusals = refusals;
    }

    @PostMapping("/services/{serviceId}/deployments/upload")
    public String upload(@PathVariable UUID serviceId,
                         @RequestParam("archive") MultipartFile archive,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        String stored = null;
        try {
            membership.requireWrite("deployment.start");
            if (service.kind() != ServiceKind.SITE) {
                throw new RequestRejected(null, "An app deploys the image it is configured with. "
                        + "There is nothing to unpack a zip into.");
            }
            if (archive.isEmpty()) {
                throw new RequestRejected("archive", "Choose a zip of the site to deploy.");
            }

            stored = read(serviceId, archive);
            Deployment queued = startDeployment.start(actor, membership.organizationId(),
                    serviceId, DeploymentRequest.fromArchive(account.id(), stored));
            InertiaFlash.success(flash, "Deployment #" + queued.sequence()
                    + " queued from your upload.");
            return "redirect:/services/" + serviceId + "/deployments/" + queued.id();
        } catch (RequestRejected | QuotaExceeded | PermissionDenied refused) {
            // The bytes are already on the panel's disk and the deployment that would have
            // consumed them does not exist. Nothing will ever come back for them.
            archives.discard(stored);
            refusals.of(actor, "deployment.start",
                    AuditTarget.of("service", serviceId, service.name()),
                    membership.organizationId(), refused, flash);
            return "redirect:/services/" + serviceId + "/deployments";
        } catch (UncheckedIOException diskFailed) {
            archives.discard(stored);
            InertiaFlash.failure(flash, "The upload could not be stored. Try again.");
            return "redirect:/services/" + serviceId + "/deployments";
        }
    }

    private String read(UUID serviceId, MultipartFile archive) {
        try (InputStream in = archive.getInputStream()) {
            return archives.store(serviceId, in).relativePath();
        } catch (IOException interrupted) {
            throw new UncheckedIOException("The upload stream ended early", interrupted);
        }
    }
}
