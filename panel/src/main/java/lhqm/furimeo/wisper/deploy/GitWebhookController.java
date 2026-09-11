package lhqm.furimeo.wisper.deploy;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditActorKind;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.service.LocateService;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceLocation;
import lhqm.furimeo.wisper.service.ServiceRepository;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * {@code /webhooks/github/{serviceId}} and {@code /webhooks/gitlab/{serviceId}}.
 *
 * <p>Public, and the only public write in the panel. There is no session and no CSRF
 * token here - a Git provider has neither and cannot be told to acquire one - so the
 * request authenticates itself with the per-service secret it carries, and
 * {@code SecurityConfig} exempts the prefix on exactly that basis.
 *
 * <p>The response is plain text with a status code, not a redirect and not an Inertia
 * page. The reader is a delivery log in somebody's repository settings, and it is the
 * first thing an operator looks at when a push did not deploy - so every outcome says in
 * one line what happened and, where it applies, what to change.
 *
 * <h2>Order of checks, which is deliberate</h2>
 *
 * <p>The signature is verified before the payload is parsed, and the body is taken as raw
 * bytes so the digest is over what actually arrived. Everything after that - the branch
 * filter, auto-deploy, whether a build is already running for this commit - answers 200,
 * because the delivery was genuine and the provider has nothing to retry. Only a bad
 * signature answers 401, and it is the one outcome that also writes a {@code DENIED}
 * audit entry.
 */
@Controller
public class GitWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitWebhookController.class);

    private final ServiceRepository services;
    private final LocateService locations;
    private final DeploymentRepository deployments;
    private final StartDeployment startDeployment;
    private final GitHubWebhook github;
    private final GitLabWebhook gitlab;
    private final SecretCipher cipher;
    private final AuditTrail audit;

    public GitWebhookController(ServiceRepository services, LocateService locations,
                                DeploymentRepository deployments, StartDeployment startDeployment,
                                GitHubWebhook github, GitLabWebhook gitlab, SecretCipher cipher,
                                AuditTrail audit) {
        this.services = services;
        this.locations = locations;
        this.deployments = deployments;
        this.startDeployment = startDeployment;
        this.github = github;
        this.gitlab = gitlab;
        this.cipher = cipher;
        this.audit = audit;
    }

    /** GitHub, authenticated by an HMAC of the body in {@code X-Hub-Signature-256}. */
    @PostMapping(path = "/webhooks/github/{serviceId}", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> github(
            @PathVariable UUID serviceId,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(name = GitHubWebhook.SIGNATURE_HEADER, required = false)
            String signature,
            @RequestHeader(name = GitHubWebhook.EVENT_HEADER, required = false) String event,
            HttpServletRequest request) {

        byte[] payload = body == null ? new byte[0] : body;
        Service service = load(serviceId);
        String secret = cipher.decrypt(service.webhookSecret());
        if (!github.isSigned(payload, signature, secret)) {
            return refuse("github", service, request,
                    "Signature does not match this service's webhook secret.");
        }
        return dispatch(service, github.read(payload, event), request, "github");
    }

    /** GitLab, authenticated by the shared secret in {@code X-Gitlab-Token}. */
    @PostMapping(path = "/webhooks/gitlab/{serviceId}", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> gitlab(
            @PathVariable UUID serviceId,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(name = GitLabWebhook.TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = GitLabWebhook.EVENT_HEADER, required = false) String event,
            HttpServletRequest request) {

        byte[] payload = body == null ? new byte[0] : body;
        Service service = load(serviceId);
        String secret = cipher.decrypt(service.webhookSecret());
        if (!gitlab.isAuthentic(token, secret)) {
            return refuse("gitlab", service, request,
                    "Token does not match this service's webhook secret.");
        }
        return dispatch(service, gitlab.read(payload, event), request, "gitlab");
    }

    /**
     * Everything after the delivery has proved who it is.
     *
     * <p>Identical for both providers by then, which is the whole reason {@link PushEvent}
     * exists: a branch filter written twice is a branch filter that is wrong once.
     */
    private ResponseEntity<String> dispatch(Service service, Optional<PushEvent> parsed,
                                            HttpServletRequest request, String provider) {
        if (parsed.isEmpty()) {
            return ok("Accepted; this delivery is not a push, so there is nothing to deploy.");
        }
        PushEvent push = parsed.get();
        if (!push.isDeployable()) {
            return ok("Accepted; that ref was deleted, so there is nothing to deploy.");
        }
        if (!service.autoDeploy()) {
            return ok("Accepted; auto-deploy is switched off for this service.");
        }
        if (!push.matches(service.repositoryBranch())) {
            return ok("Accepted; this service deploys " + service.repositoryBranch()
                    + " and that push was " + push.shortRef() + ".");
        }
        if (isAlreadyRunning(service.id(), push.commitSha())) {
            return ok("Accepted; a deployment of " + push.commitSha() + " is already running.");
        }

        DeploymentSource source =
                DeploymentSource.forKind(service.kind(), service.repositoryUrl() != null);
        if (source == DeploymentSource.ARCHIVE) {
            // A site with a webhook and no repository. The hook is genuine and there is
            // nothing to clone, so this is a configuration to fix rather than a retry.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    "This site has no repository configured, so a push cannot build it. "
                            + "Add the repository in the service's settings.\n");
        }

        ServiceLocation location = locations.byId(service.id());
        AuditActor actor = actorFor(provider, request);
        try {
            Deployment queued = startDeployment.start(actor, location.organizationId(),
                    service.id(), DeploymentRequest.fromPush(source, push.revision()));
            return ResponseEntity.accepted().body("Queued deployment #" + queued.sequence()
                    + " of " + push.shortRef() + ".\n");
        } catch (QuotaExceeded | RequestRejected refused) {
            String message = refused.getMessage() == null ? "Refused." : refused.getMessage();
            audit.record(AuditEntry.denied(actor, "deployment.start",
                    AuditTarget.of("service", service.id(), service.name()),
                    location.organizationId(), message));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(message + "\n");
        }
    }

    /**
     * Whether a build of this exact commit is already under way.
     *
     * <p>Both providers retry a delivery that timed out, and a repository can be
     * configured with the same hook twice. Neither should produce two builds of one
     * commit, and the cheap way to say so is to look at what is in flight - which is a
     * handful of rows on an index that exists for the worker.
     */
    private boolean isAlreadyRunning(UUID serviceId, String commitSha) {
        if (commitSha.isEmpty()) {
            return false;
        }
        return deployments.findInFlight(serviceId).stream()
                .anyMatch(candidate -> commitSha.equals(candidate.commitSha()));
    }

    private ResponseEntity<String> refuse(String provider, Service service,
                                          HttpServletRequest request, String message) {
        log.warn("Rejected a {} webhook for service {}: {}", provider, service.id(), message);
        audit.record(AuditEntry.denied(actorFor(provider, request), "deployment.start",
                AuditTarget.of("service", service.id(), service.name()), null, message));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(message + "\n");
    }

    /**
     * The actor for a delivery: {@code SYSTEM}, because there is no person behind a
     * webhook and no account it can be attributed to - only a provider and an address.
     * Both go on the entry, because "who kept posting to this hook" is the question the
     * refusals are kept for.
     */
    private static AuditActor actorFor(String provider, HttpServletRequest request) {
        return new AuditActor(AuditActorKind.SYSTEM, null, null, null, provider + "-webhook",
                request.getRemoteAddr(), request.getHeader("User-Agent"),
                request.getHeader(AuditActor.REQUEST_ID_HEADER));
    }

    private Service load(UUID serviceId) {
        return services.findById(serviceId)
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
    }

    private static ResponseEntity<String> ok(String message) {
        return ResponseEntity.ok(message + "\n");
    }
}
