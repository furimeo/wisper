package lhqm.furimeo.wisper.placement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceRepository;

/**
 * Everything one node has to run, read in three statements.
 *
 * <p>Three and not one per service. A generation is cut whenever anything changes anywhere
 * on a node, which on a busy fleet is constantly, and a loop of queries over two hundred
 * services would make publishing a spec the panel's slowest write. The count is fixed
 * whatever the node is holding.
 *
 * <p>It also does not read {@code service} through SQL of its own: {@link ServiceRepository}
 * already maps the row, including the {@code text[]} columns that would otherwise need an
 * array reader here as well as in {@link LoadNodeCapacity}.
 */
@Component
public class LoadPlacedServices {

    private static final String TENANTS = """
            SELECT s.id AS service_id, p.organization_id
              FROM service s
              JOIN project p ON p.id = s.project_id
             WHERE s.id IN (:serviceIds)
            """;

    /**
     * The live release per service. {@code deployment_current_idx} is unique and partial on
     * {@code is_current}, so this is at most one row each and no {@code DISTINCT} is
     * papering over anything.
     */
    private static final String CURRENT_RELEASES = """
            SELECT service_id, id AS deployment_id, image_digest
              FROM deployment
             WHERE service_id IN (:serviceIds)
               AND is_current
            """;

    private final PlacementRepository placements;
    private final ServiceRepository services;
    private final JdbcClient jdbc;

    public LoadPlacedServices(PlacementRepository placements, ServiceRepository services,
                              JdbcClient jdbc) {
        this.placements = placements;
        this.services = services;
        this.jdbc = jdbc;
    }

    /**
     * The workloads bound to this node, ordered by service id.
     *
     * <p>The order is the spec's order, and it is a stable one on purpose: a spec that
     * lists the same workloads in a different sequence hashes differently, and a node that
     * hashes its spec to detect drift would then see a change where there is none.
     *
     * <p>Archived services are dropped. An archived service is one the customer has put
     * away, and leaving it in a spec means it keeps running and keeps costing them.
     */
    @Transactional(readOnly = true)
    public List<PlacedService> onNode(UUID nodeId) {
        List<Placement> bindings = placements.findLiveOn(nodeId);
        if (bindings.isEmpty()) {
            return List.of();
        }

        Map<UUID, Service> byId = new HashMap<>();
        for (Service service : services.findAllById(bindings.stream()
                .map(Placement::serviceId).toList())) {
            if (!service.isArchived()) {
                byId.put(service.id(), service);
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }

        List<UUID> serviceIds = List.copyOf(byId.keySet());
        Map<UUID, UUID> tenants = new HashMap<>();
        for (Tenant tenant : jdbc.sql(TENANTS).param("serviceIds", serviceIds)
                .query(LoadPlacedServices::tenantRow).list()) {
            tenants.put(tenant.serviceId(), tenant.organizationId());
        }
        Map<UUID, Release> releases = new HashMap<>();
        for (Release release : jdbc.sql(CURRENT_RELEASES).param("serviceIds", serviceIds)
                .query(LoadPlacedServices::releaseRow).list()) {
            releases.put(release.serviceId(), release);
        }

        List<PlacedService> placed = new ArrayList<>(byId.size());
        for (Placement binding : bindings) {
            Service service = byId.get(binding.serviceId());
            if (service == null) {
                continue;
            }
            Release release = releases.get(service.id());
            placed.add(new PlacedService(binding, service, tenants.get(service.id()),
                    release == null ? "" : release.deploymentId().toString(),
                    release == null ? "" : nullToEmpty(release.imageDigest())));
        }
        placed.sort(Comparator.comparing(entry -> entry.service().id()));
        return List.copyOf(placed);
    }

    private static Tenant tenantRow(ResultSet row, int rowNumber) throws SQLException {
        return new Tenant(row.getObject("service_id", UUID.class),
                row.getObject("organization_id", UUID.class));
    }

    private static Release releaseRow(ResultSet row, int rowNumber) throws SQLException {
        return new Release(row.getObject("service_id", UUID.class),
                row.getObject("deployment_id", UUID.class),
                row.getString("image_digest"));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Which tenant a service belongs to, which is two joins away and needed once per spec. */
    private record Tenant(UUID serviceId, UUID organizationId) {
    }

    /** The deployment currently serving a service. */
    private record Release(UUID serviceId, UUID deploymentId, String imageDigest) {
    }
}
