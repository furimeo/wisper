package lhqm.furimeo.wisper.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.jobs.JobQueue;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceRepository;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Points a hostname at a service.
 *
 * <p>Four things have to be true before a row exists, and the order they are checked in is
 * the order that produces the most useful message: the name has to be a name a certificate
 * authority could ever issue for, nobody else may hold it, the plan has to allow another
 * one, and the service has to be one this member may change.
 *
 * <h2>Global uniqueness is the security control</h2>
 *
 * <p>{@code domain_hostname_key} is not scoped to an organization. Two tenants both holding
 * {@code example.com} would mean the node's route table answering for whichever it loaded
 * last, one customer serving another's traffic, and certificates being obtained in somebody
 * else's name. The check here turns that into a sentence; the index is what makes it true
 * even when two requests race.
 *
 * <h2>The first hostname becomes the primary</h2>
 *
 * <p>Automatically, because a service with addresses and no address to show is a screen
 * that has to say "none of these is the main one" for no reason. Every later hostname is an
 * alias until somebody says otherwise.
 */
@Component
public class AddDomain {

    private final ServiceRepository services;
    private final DomainRepository domains;
    private final QuotaGuard quotas;
    private final JobQueue jobs;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public AddDomain(ServiceRepository services, DomainRepository domains, QuotaGuard quotas,
                     JobQueue jobs, PublishNodeSpec specs, AuditTrail audit) {
        this.services = services;
        this.domains = domains;
        this.quotas = quotas;
        this.jobs = jobs;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @param rawHostname        whatever the customer typed; normalised by {@link Hostname}
     * @param tlsMode            {@link DomainTlsMode#ON_DEMAND} unless the customer is
     *                           staging a cut-over and wants plain HTTP for now
     * @param targetPort         overrides the service's container port for this hostname
     *                           only; null for the service's own port
     * @param forceHttps         redirect plain HTTP once the hostname is verified
     * @param redirectToHostname serve a permanent redirect instead of the service
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service is not this
     *                                                  organization's
     * @throws HostnameTaken                            when somebody already holds the name
     * @throws RequestRejected                          for a wildcard, a malformed name, or
     *                                                  a port outside 1-65535
     * @throws lhqm.furimeo.wisper.org.QuotaExceeded    at the plan's domain ceiling
     */
    @Transactional
    public Domain add(AuditActor actor, Membership membership, UUID serviceId, String rawHostname,
                      DomainTlsMode tlsMode, Integer targetPort, boolean forceHttps,
                      String redirectToHostname) {
        membership.requireWrite("domain.add");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        if (service.isArchived()) {
            throw new RequestRejected(null, "This service is archived. Restore it before pointing "
                    + "a hostname at it, or the name would resolve to nothing.");
        }

        String hostname = Hostname.require(rawHostname);
        String redirect = redirectTarget(hostname, redirectToHostname);
        Integer port = checkedPort(targetPort);

        if (domains.existsByHostname(hostname)) {
            throw new HostnameTaken(hostname);
        }
        quotas.require(membership.organizationId(), QuotaResource.DOMAIN, 1);

        DomainKind kind = domains.findPrimaryOf(serviceId).isPresent()
                ? DomainKind.ALIAS : DomainKind.PRIMARY;
        Domain saved = insert(Domain.of(UUID.randomUUID(), serviceId, hostname, kind,
                tlsMode == null ? DomainTlsMode.ON_DEMAND : tlsMode,
                VerificationToken.issue(), forceHttps, port, redirect));

        // Enqueued inside this transaction: the check and the row it checks commit together
        // or neither exists. `enqueueIfAbsent` rather than `enqueue`, because a hostname
        // removed and re-added within one recheck interval still has the previous job
        // sitting in the table under the same domain id - and re-adding is not a failure.
        DomainCheckJob check = new DomainCheckJob(saved.id());
        jobs.enqueueIfAbsent(DomainVerificationTasks.VERIFY, check.instanceId(), check,
                Instant.now());

        specs.forService(serviceId, "hostname " + hostname + " added to " + service.slug());

        audit.record(AuditEntry.succeeded(actor, "domain.add",
                AuditTarget.of("domain", saved.id(), hostname), membership.organizationId(),
                "Pointed at " + service.slug() + " as the " + kind + " hostname, TLS "
                        + saved.tlsMode()));
        return saved;
    }

    /**
     * The insert, with the race the pre-check cannot cover.
     *
     * <p>Two requests for one hostname can both pass {@code existsByHostname} and one of them
     * will lose at the index. Translating that back into the same {@link HostnameTaken} means
     * the loser gets the sentence the other 99.99% of callers get, instead of a stack trace
     * about a constraint.
     */
    private Domain insert(Domain domain) {
        try {
            return domains.save(domain);
        } catch (DuplicateKeyException raced) {
            throw new HostnameTaken(domain.hostname());
        }
    }

    /**
     * The redirect target, normalised the same way as the hostname itself.
     *
     * <p>{@code domain_redirect_lowercase} requires it lower-cased, and a redirect to the
     * hostname's own name is a loop the browser would follow until it gave up.
     */
    private static String redirectTarget(String hostname, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String target;
        try {
            target = Hostname.require(raw);
        } catch (RequestRejected malformed) {
            // Same rules, different input: the message has to appear under the redirect box
            // rather than under the hostname box the customer filled in correctly.
            throw new RequestRejected("redirectToHostname", malformed.getMessage());
        }
        if (target.equals(hostname)) {
            throw new RequestRejected("redirectToHostname",
                    "A hostname cannot redirect to itself.");
        }
        return target;
    }

    private static Integer checkedPort(Integer targetPort) {
        if (targetPort == null) {
            return null;
        }
        if (targetPort < 1 || targetPort > 65535) {
            throw new RequestRejected("targetPort", "A port is between 1 and 65535.");
        }
        return targetPort;
    }
}
