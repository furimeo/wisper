package lhqm.furimeo.wisper.deploy;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceRepository;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The deployments screen: {@code /services/{serviceId}/deployments} and its three writes.
 *
 * <p>Nested under the service on purpose (panel-http.md). A flat
 * {@code /deployments?serviceId=…} can be reached without a service id, which is how the
 * handler that forgets to check ownership gets written; here there is always a service to
 * resolve a {@link Membership} against, and every method below starts by doing so.
 *
 * <p>Uploading a zip is {@link DeploymentArchiveController} and the live log is
 * {@link DeploymentLogController}. Same prefix, different screens, and keeping them apart
 * keeps a multipart parser and an SSE stream out of the file that answers "what has this
 * service deployed".
 */
@Controller
public class DeploymentController {

    /** How much history one page shows. Enough to scroll, few enough for a phone. */
    private static final int PAGE_SIZE = 50;

    /** How much of a finished build's log the detail page renders before SSE takes over. */
    private static final int LOG_PREVIEW = 500;

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ServiceRepository services;
    private final ListDeployments listing;
    private final DeploymentLogRepository lines;
    private final StartDeployment startDeployment;
    private final CancelDeployment cancelDeployment;
    private final RollbackRelease rollbackRelease;
    private final QuotaGuard quotas;
    private final RecordRefusal refusals;

    public DeploymentController(ResolveCurrentAccount currentAccount,
                                ResolveMembership memberships, ServiceRepository services,
                                ListDeployments listing, DeploymentLogRepository lines,
                                StartDeployment startDeployment, CancelDeployment cancelDeployment,
                                RollbackRelease rollbackRelease, QuotaGuard quotas,
                                RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.services = services;
        this.listing = listing;
        this.lines = lines;
        this.startDeployment = startDeployment;
        this.cancelDeployment = cancelDeployment;
        this.rollbackRelease = rollbackRelease;
        this.quotas = quotas;
        this.refusals = refusals;
    }

    /**
     * The history.
     *
     * <p>Props: {@code service} (a {@link DeploymentTarget}), {@code deployments},
     * {@code viewerRole}, {@code deploymentAllowance} - the last so the page can grey out
     * the button with the reason rather than let somebody press it and be refused.
     *
     * <p>Renders {@code features/deploy/DeploymentListPage.tsx}.
     */
    @GetMapping("/services/{serviceId}/deployments")
    public String list(@PathVariable UUID serviceId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        Service service = load(membership, serviceId);

        model.addAttribute("service", DeploymentTarget.of(service));
        model.addAttribute("deployments", listing.recent(serviceId, PAGE_SIZE));
        model.addAttribute("viewerRole", membership.role());
        model.addAttribute("deploymentAllowance", quotas.allowanceFor(
                membership.organizationId(), QuotaResource.DEPLOYMENTS_PER_DAY));
        return "deploy/DeploymentList";
    }

    /**
     * One deployment and its build log.
     *
     * <p>The log is rendered from the table rather than waited for over SSE, so a finished
     * build is readable the instant the page paints and a build still running has
     * something on screen before the first new line arrives. The page then opens
     * {@link DeploymentLogController} with {@code logCursor} and receives only what it has
     * not already got.
     *
     * <p>Renders {@code features/deploy/DeploymentDetailPage.tsx}.
     */
    @GetMapping("/services/{serviceId}/deployments/{deploymentId}")
    public String detail(@PathVariable UUID serviceId, @PathVariable UUID deploymentId,
                         Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        Service service = load(membership, serviceId);

        DeploymentSummary deployment = listing.one(serviceId, deploymentId)
                .orElseThrow(() -> NotFoundException.of("deployment", deploymentId));
        List<DeploymentLog> output = lines.findAfter(deploymentId, 0, LOG_PREVIEW);

        model.addAttribute("service", DeploymentTarget.of(service));
        model.addAttribute("deployment", deployment);
        model.addAttribute("log", output);
        model.addAttribute("logCursor", output.isEmpty() ? 0L : output.getLast().sequence());
        model.addAttribute("viewerRole", membership.role());
        return "deploy/DeploymentDetail";
    }

    /**
     * Deploy now.
     *
     * <p>{@code ref} is optional and only means anything for a site with a repository: it
     * is the branch or tag to build, and the service's configured branch is used when it
     * is left empty. An app ignores it and redeploys the image it is configured with.
     */
    @PostMapping("/services/{serviceId}/deployments")
    public String deploy(@PathVariable UUID serviceId,
                         @RequestParam(name = "ref", required = false) String ref,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Service service = load(membership, serviceId);
        try {
            membership.requireWrite("deployment.start");
            DeploymentTarget target = DeploymentTarget.of(service);
            DeploymentSource source = DeploymentSource.forKind(service.kind(),
                    service.repositoryUrl() != null);
            String chosen = ref == null || ref.isBlank() ? target.defaultRef() : ref.strip();
            GitRevision revision = source == DeploymentSource.GIT
                    ? GitRevision.ofRef(chosen)
                    : GitRevision.unknown();

            Deployment queued = startDeployment.start(actor, membership.organizationId(),
                    serviceId, DeploymentRequest.manual(account.id(), source, revision));
            InertiaFlash.success(flash, "Deployment #" + queued.sequence() + " queued.");
            return "redirect:/services/" + serviceId + "/deployments/" + queued.id();
        } catch (RequestRejected | QuotaExceeded | PermissionDenied refused) {
            refusals.of(actor, "deployment.start",
                    AuditTarget.of("service", serviceId, service.name()),
                    membership.organizationId(), refused, flash);
            return "redirect:/services/" + serviceId + "/deployments";
        }
    }

    /** Stop a deployment that has not finished. */
    @PostMapping("/services/{serviceId}/deployments/{deploymentId}/cancel")
    public String cancel(@PathVariable UUID serviceId, @PathVariable UUID deploymentId,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        try {
            Deployment cancelled = cancelDeployment.cancel(actor, membership, serviceId,
                    deploymentId);
            InertiaFlash.success(flash, "Deployment #" + cancelled.sequence() + " cancelled.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "deployment.cancel",
                    AuditTarget.of("service", serviceId, deploymentId.toString()),
                    membership.organizationId(), refused, flash);
        }
        return "redirect:/services/" + serviceId + "/deployments/" + deploymentId;
    }

    /** Put an earlier release back in front of customers. */
    @PostMapping("/services/{serviceId}/deployments/{deploymentId}/rollback")
    public String rollback(@PathVariable UUID serviceId, @PathVariable UUID deploymentId,
                           HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        try {
            Deployment queued = rollbackRelease.to(actor, membership, serviceId, deploymentId);
            InertiaFlash.success(flash, "Rolling back as deployment #" + queued.sequence() + ".");
            return "redirect:/services/" + serviceId + "/deployments/" + queued.id();
        } catch (RequestRejected | QuotaExceeded | PermissionDenied refused) {
            refusals.of(actor, "deployment.rollback",
                    AuditTarget.of("service", serviceId, deploymentId.toString()),
                    membership.organizationId(), refused, flash);
            return "redirect:/services/" + serviceId + "/deployments/" + deploymentId;
        }
    }

    private Service load(Membership membership, UUID serviceId) {
        return services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
    }
}
