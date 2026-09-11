package lhqm.furimeo.wisper.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the domains page in four statements.
 *
 * <p>Four, and not four per hostname: the page shows every hostname on a service with its
 * certificate and whether the edge is answering for it, and a service with a dozen aliases
 * would otherwise be two dozen round trips for one screen.
 *
 * <h2>Where {@code serving} comes from</h2>
 *
 * <p>There is no {@code domain.serving} column. {@code domain} is entirely panel-owned and
 * {@code certificate} is the only node-written table keyed by a hostname, so what the node
 * reported has to be read off the state that report left behind:
 *
 * <ul>
 * <li>nothing holds the service - not serving, whatever else is true;</li>
 * <li>TLS switched off - serving as soon as a node holds it, because there is nothing else
 *     that can fail;</li>
 * <li>otherwise - serving when that same node has a live certificate for the hostname, which
 *     it only ever gets by having answered an ACME challenge on it.</li>
 * </ul>
 *
 * <p>The one thing this misreads is a route the edge has loaded whose container has crashed.
 * That is a workload status, it belongs to {@code placement}, and the page shows it in its
 * own right rather than folding two different failures into one green dot.
 */
@Component
public class ListServiceDomains {

    private final DomainRepository domains;
    private final CertificateRepository certificates;

    public ListServiceDomains(DomainRepository domains, CertificateRepository certificates) {
        this.domains = domains;
        this.certificates = certificates;
    }

    /** Every hostname on a service, with its certificate and where DNS has to point. */
    @Transactional(readOnly = true)
    public ServiceDomains of(UUID serviceId) {
        List<Domain> rows = domains.findByServiceId(serviceId);
        String nodeAddress = domains.findActiveNodeAddressOf(serviceId).orElse(null);
        if (rows.isEmpty()) {
            return new ServiceDomains(List.of(), nodeAddress);
        }

        UUID nodeId = domains.findActiveNodeOf(serviceId).orElse(null);
        Map<UUID, Certificate> shown = mostRelevantPerDomain(rows);

        List<DomainView> views = new ArrayList<>(rows.size());
        for (Domain domain : rows) {
            Certificate certificate = shown.get(domain.id());
            views.add(DomainView.of(domain, certificate, isServing(domain, certificate, nodeId)));
        }
        return new ServiceDomains(views, nodeAddress);
    }

    /**
     * One certificate row per hostname: the live one, or failing that the most recent.
     *
     * <p>The most recent matters as much as the live one. A hostname whose issuance keeps
     * failing has nothing live, and the error on its last attempt is the entire reason the
     * customer opened this page.
     */
    private Map<UUID, Certificate> mostRelevantPerDomain(List<Domain> rows) {
        List<UUID> ids = new ArrayList<>(rows.size());
        for (Domain domain : rows) {
            ids.add(domain.id());
        }
        Map<UUID, Certificate> best = new HashMap<>();
        for (Certificate candidate : certificates.findByDomainIdIn(ids)) {
            best.merge(candidate.domainId(), candidate, ListServiceDomains::preferred);
        }
        return best;
    }

    /** Live beats not live; otherwise the one touched most recently. */
    private static Certificate preferred(Certificate existing, Certificate candidate) {
        if (existing.isLive() != candidate.isLive()) {
            return existing.isLive() ? existing : candidate;
        }
        return touchedAt(candidate).isAfter(touchedAt(existing)) ? candidate : existing;
    }

    private static Instant touchedAt(Certificate certificate) {
        if (certificate.updatedAt() != null) {
            return certificate.updatedAt();
        }
        return certificate.createdAt() == null ? Instant.EPOCH : certificate.createdAt();
    }

    /** The derivation described in the class comment. */
    private static boolean isServing(Domain domain, Certificate certificate, UUID nodeId) {
        if (nodeId == null) {
            return false;
        }
        if (!domain.wantsCertificate()) {
            return true;
        }
        return certificate != null && certificate.isLive()
                && Objects.equals(certificate.nodeId(), nodeId);
    }
}
