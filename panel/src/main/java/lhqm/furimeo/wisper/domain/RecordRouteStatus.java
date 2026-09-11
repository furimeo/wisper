package lhqm.furimeo.wisper.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.proto.v1.RouteStatus;

/**
 * The {@code routes} half of a {@code StatusBatch}, split per hostname.
 *
 * <p>Called by {@code grpc} and by nothing else (panel-ports.md §3). It is an adapter: it
 * decides which reports are this node's to make and hands each one to
 * {@link RecordCertificateStatus}, which owns the table.
 *
 * <h2>A node may only report on hostnames it was given</h2>
 *
 * <p>A node authenticates as itself and then names hostnames in the body of the message.
 * Without the check below, a compromised or buggy node could write certificate records
 * against another machine's domains - marking a competitor's hostname as failing, or
 * claiming an expiry that would silence a real warning. The set of hostnames a node may
 * speak about is exactly the set {@code BuildRoutes} put in its spec, which is what
 * {@link DomainRepository#findRoutedTo} returns.
 *
 * <p>Anything else is dropped rather than rejected: a report can legitimately arrive a
 * second after a customer removed a hostname, and failing the whole batch over one stale
 * entry would throw away the other two hundred.
 */
@Component
public class RecordRouteStatus {

    private static final Logger log = LoggerFactory.getLogger(RecordRouteStatus.class);

    private final DomainRepository domains;
    private final RecordCertificateStatus certificates;

    public RecordRouteStatus(DomainRepository domains, RecordCertificateStatus certificates) {
        this.domains = domains;
        this.certificates = certificates;
    }

    /**
     * @param statuses   every route the node's edge reported in one reconcile pass
     * @param observedAt when the node made the observations
     */
    @Transactional
    public void accept(UUID nodeId, List<RouteStatus> statuses, Instant observedAt) {
        if (nodeId == null || statuses == null || statuses.isEmpty()) {
            return;
        }
        Map<String, Domain> routed = new HashMap<>();
        for (Domain domain : domains.findRoutedTo(nodeId)) {
            routed.put(domain.hostname(), domain);
        }

        List<String> notServing = new ArrayList<>();
        int ignored = 0;
        for (RouteStatus status : statuses) {
            Domain domain = routed.get(canonical(status.getDomain()));
            if (domain == null) {
                ignored++;
                continue;
            }
            if (!status.getServing()) {
                notServing.add(domain.hostname());
            }
            certificates.accept(nodeId, domain, status, observedAt);
        }

        if (ignored > 0) {
            // Not an error on its own. Logged because a node persistently naming hostnames
            // it was never given is worth somebody looking at.
            log.info("Node {} reported {} route status(es) for hostnames it is not routing",
                    nodeId, ignored);
        }
        if (!notServing.isEmpty() && log.isDebugEnabled()) {
            // The edge has the hostname but no backend to send it to, which is a workload
            // problem and lands in `placement` as a workload status. Recorded here only as a
            // breadcrumb for the person reading both halves of one reconcile pass.
            log.debug("Node {} is not currently serving {}", nodeId, notServing);
        }
    }

    /**
     * The form a hostname is stored in.
     *
     * <p>Caddy hands back whatever the SNI carried, and a fully-qualified name with a
     * trailing dot is the same name. Matching that against a stored hostname byte for byte
     * would silently drop every report for it.
     */
    private static String canonical(String reported) {
        String value = reported == null ? "" : reported.trim().toLowerCase(Locale.ROOT);
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }
}
