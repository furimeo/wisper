package lhqm.furimeo.wisper.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Checks whether a domain's DNS resolves to the node address hosting the service.
 */
@Component
public class CheckDomainDns {

    private final DomainRepository domains;

    public CheckDomainDns(DomainRepository domains) {
        this.domains = domains;
    }

    public record Result(boolean resolved, String nodeAddress, List<String> addresses) {}

    public Result check(UUID serviceId, UUID domainId) {
        Domain domain = domains.findById(domainId)
                .filter(d -> d.serviceId().equals(serviceId))
                .orElseThrow(() -> NotFoundException.of("domain", domainId));

        String nodeAddress = domains.findActiveNodeAddressOf(serviceId).orElse(null);
        if (nodeAddress == null) {
            return new Result(false, null, List.of());
        }

        try {
            InetAddress[] resolved = InetAddress.getAllByName(domain.hostname());
            List<String> addresses = Arrays.stream(resolved).map(InetAddress::getHostAddress).toList();
            boolean matches = addresses.stream().anyMatch(addr -> addr.equals(nodeAddress));
            return new Result(matches, nodeAddress, addresses);
        } catch (UnknownHostException e) {
            return new Result(false, nodeAddress, List.of());
        }
    }
}
