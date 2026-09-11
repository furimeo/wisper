package lhqm.furimeo.wisper.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.service.LocateService;
import lhqm.furimeo.wisper.service.ServiceLocation;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Asks DNS whether a hostname really points at the node holding its service.
 *
 * <p>This is the check that keeps one customer from taking another's name. Global
 * uniqueness on {@code domain.hostname} stops two tenants both holding {@code example.com};
 * this is what says whether the one who holds it has any claim to it. Until it passes, the
 * hostname is routed but not redirected to HTTPS (node-spec.md §3.6), and an operator
 * looking at a disputed name can see that nobody ever proved control of it.
 *
 * <h2>Two proofs, because one is not always available</h2>
 *
 * <ol>
 * <li><strong>Address records.</strong> The hostname resolves to an address the node
 *     answers on. This is the ordinary case and it proves the thing that actually matters:
 *     traffic for that name arrives here.</li>
 * <li><strong>A challenge TXT record.</strong> For a hostname that is still serving a live
 *     site somewhere else and cannot be repointed until the replacement is ready - which is
 *     exactly the migration this platform exists to make possible.</li>
 * </ol>
 *
 * <h2>Verification is sticky, and that is deliberate</h2>
 *
 * <p>A verified hostname is never re-checked and never demoted. {@code force_https} follows
 * this flag, so demoting one because DNS moved for ten minutes during a migration would
 * switch HTTPS redirects off underneath a working site. Releasing a name means removing the
 * domain, which is a deliberate act with an audit entry behind it.
 */
@Component
public class VerifyDomainOwnership {

    private static final Logger log = LoggerFactory.getLogger(VerifyDomainOwnership.class);

    private final DomainRepository domains;
    private final DnsLookup dns;
    private final DomainSettings settings;
    private final LocateService services;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public VerifyDomainOwnership(DomainRepository domains, DnsLookup dns, DomainSettings settings,
                                 LocateService services, PublishNodeSpec specs, AuditTrail audit) {
        this.domains = domains;
        this.dns = dns;
        this.settings = settings;
        this.services = services;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * The automatic path, run by the {@code domain-verify} job.
     *
     * <p>Audits only when the state moved. A recheck that finds the same thing every five
     * minutes for every unverified hostname on the platform would bury the trail it is part
     * of.
     */
    @Transactional
    public VerificationResult check(UUID domainId) {
        Optional<Domain> stored = domains.findById(domainId);
        if (stored.isEmpty()) {
            return VerificationResult.gone();
        }
        Domain domain = stored.get();
        VerificationResult result = apply(domain, Instant.now());
        if (result.changed()) {
            ServiceLocation location = services.byId(domain.serviceId());
            audit.record(AuditEntry.succeeded(AuditActor.system("domain verification"),
                    "domain.verify", target(domain), location.organizationId(),
                    domain.hostname() + " is now " + result.state() + ". " + result.detail()));
        }
        return result;
    }

    /**
     * The manual path, behind the "Check now" button.
     *
     * <p>Always audited, whatever it found: somebody asked, and a refused or fruitless
     * attempt is as much a part of the trail as a successful one.
     *
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        if the hostname is not this
     *                                                  organization's
     */
    @Transactional
    public VerificationResult verifyNow(AuditActor actor, Membership membership, UUID serviceId,
                                        UUID domainId) {
        membership.requireWrite("domain.verify");
        Domain domain = domains.findOwnedBy(domainId, serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("domain", domainId));

        VerificationResult result = apply(domain, Instant.now());
        audit.record(AuditEntry.succeeded(actor, "domain.verify", target(domain),
                membership.organizationId(),
                "Checked " + domain.hostname() + ": " + result.state() + ". " + result.detail()));
        return result;
    }

    /** Runs the check and writes whatever it concluded. */
    private VerificationResult apply(Domain domain, Instant now) {
        if (domain.isVerified()) {
            return VerificationResult.verified(domain.hostname(),
                    "It was verified on " + domain.verifiedAt() + ".", false);
        }
        VerificationResult result = evaluate(domain, now);
        switch (result.state()) {
            case VERIFIED -> {
                domains.save(domain.verified(now));
                // force_https in the node's route table is off until a hostname is proven,
                // so proving one changes the spec (node-spec.md §3.6).
                specs.forService(domain.serviceId(), domain.hostname() + " verified");
            }
            case FAILED -> domains.save(domain.failedCheck(now, result.detail()));
            case PENDING -> domains.save(domain.awaitingCheck(now, result.detail()));
        }
        return result;
    }

    /**
     * The lookup itself, with no writing.
     *
     * <p>A resolver that cannot be reached lands on {@code PENDING}, never on
     * {@code FAILED}: "I could not ask" is not "the answer is no", and treating the two the
     * same would fail every domain on the platform the moment the panel's own network
     * hiccuped.
     */
    private VerificationResult evaluate(Domain domain, Instant now) {
        Duration recheckIn = settings.recheckAfter(age(domain, now));
        boolean movesToPending = domain.verificationState() != DomainVerification.PENDING;

        ServiceLocation location = services.byId(domain.serviceId());
        if (!location.isPlaced()) {
            return VerificationResult.pending(domain.hostname(), "This service is not running on "
                    + "a node yet, so there is no address to point " + domain.hostname() + " at.",
                    movesToPending, recheckIn);
        }
        String publicAddress = domains.findNodeAddress(domain.id()).orElse("");
        if (publicAddress.isBlank()) {
            return VerificationResult.pending(domain.hostname(), "The node holding this service "
                    + "has no public address recorded, so there is nothing to compare against. "
                    + "An operator sets that on the node.", movesToPending, recheckIn);
        }

        try {
            List<String> expected = expectedAddresses(publicAddress);
            if (expected.isEmpty()) {
                return VerificationResult.pending(domain.hostname(), "The node's own address, "
                        + publicAddress + ", does not resolve, so wisper cannot say where this "
                        + "hostname should point.", movesToPending, recheckIn);
            }
            return compare(domain, expected, recheckIn);
        } catch (DnsUnavailable unreachable) {
            log.info("DNS check for {} could not be completed: {}", domain.hostname(),
                    unreachable.getMessage());
            return VerificationResult.pending(domain.hostname(), "No DNS resolver answered, so "
                    + "nothing could be checked. wisper will try again shortly.",
                    movesToPending, recheckIn);
        }
    }

    /** Address records first, then the challenge record. */
    private VerificationResult compare(Domain domain, List<String> expected, Duration recheckIn) {
        List<String> observed = dns.addresses(domain.hostname());
        for (String address : observed) {
            if (expected.contains(address)) {
                return VerificationResult.verified(domain.hostname(), "It resolves to " + address
                        + ", which is this service's node.", true);
            }
        }
        String challenge = findChallenge(domain);
        if (challenge != null) {
            return VerificationResult.verified(domain.hostname(),
                    "The challenge record was found at " + challenge + ".", true);
        }

        boolean changed = domain.verificationState() != DomainVerification.FAILED;
        String wanted = VerificationResult.describeAddresses(expected);
        if (observed.isEmpty()) {
            return VerificationResult.failed(domain.hostname(), "It does not resolve at all yet. "
                    + "Point it at " + wanted + " with an A or AAAA record, or publish the TXT "
                    + "record shown below.", changed, recheckIn);
        }
        return VerificationResult.failed(domain.hostname(), "It resolves to "
                + VerificationResult.describeAddresses(observed) + ", not " + wanted
                + ". Until it points here, wisper cannot obtain a certificate for it.",
                changed, recheckIn);
    }

    /**
     * The challenge name that carried the token, or null.
     *
     * <p>Two names are accepted: under the hostname itself, and one label up. Some DNS
     * interfaces refuse to create a record beneath a name that has none, and proving control
     * of the parent is a stronger claim than proving control of the child, not a weaker one.
     */
    private String findChallenge(Domain domain) {
        String token = domain.verificationToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        for (String name : List.of(VerificationToken.recordName(domain.hostname()),
                VerificationToken.parentRecordName(domain.hostname()))) {
            if (VerificationToken.isPresentIn(dns.textRecords(name), token)) {
                return name;
            }
        }
        return null;
    }

    /**
     * Every address the node answers on.
     *
     * <p>{@code node.public_address} holds either a literal or a name. A name is resolved,
     * because an operator who gave their fleet DNS names should not have to keep the panel's
     * copy of the address in step with reality by hand.
     */
    private List<String> expectedAddresses(String publicAddress) {
        String address = publicAddress.trim();
        if (DnsLookup.isAddressLiteral(address)) {
            return List.of(DnsLookup.normaliseAddress(address));
        }
        return new ArrayList<>(dns.addresses(address));
    }

    private static Duration age(Domain domain, Instant now) {
        return domain.createdAt() == null
                ? Duration.ZERO : Duration.between(domain.createdAt(), now);
    }

    private static AuditTarget target(Domain domain) {
        return AuditTarget.of("domain", domain.id(), domain.hostname());
    }
}
