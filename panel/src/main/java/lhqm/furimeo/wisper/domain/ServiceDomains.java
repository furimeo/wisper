package lhqm.furimeo.wisper.domain;

import java.util.List;

/**
 * Everything the domains page needs about one service, read together.
 *
 * <p>Two values rather than two calls, because they come out of one look at the same rows:
 * whether a hostname is being served depends on which node holds the service, and the
 * address the customer has to put in an A record is that node's. Fetching them separately
 * would be two round trips and, worse, two chances to disagree about which node that is.
 *
 * @param domains     every hostname on the service, primary first then alphabetical
 * @param nodeAddress {@code node.public_address} of the service's active placement, or null
 *                    when nothing holds it yet. This is what the page tells the customer to
 *                    point DNS at; the panel itself never dials it.
 */
public record ServiceDomains(List<DomainView> domains, String nodeAddress) {

    public ServiceDomains {
        domains = domains == null ? List.of() : List.copyOf(domains);
    }
}
