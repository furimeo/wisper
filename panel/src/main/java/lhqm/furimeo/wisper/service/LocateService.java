package lhqm.furimeo.wisper.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Answers "where is this service" for the five packages that hang off one.
 *
 * <p>{@code deploy} needs the node to send a build to, {@code files} and {@code stats}
 * need the node to open a stream against, {@code domain} needs the tenant to authorise
 * against and {@code backup} needs all of it. Written five times, this query would be
 * five chances to forget that a {@code DRAINING} placement is not the answer.
 *
 * <p>One statement, one left join. The placement is joined only in its {@code ACTIVE}
 * state, which the {@code placement_one_active_per_service_idx} partial unique index
 * guarantees is at most one row - so the join cannot multiply the result and there is no
 * {@code LIMIT} papering over a duplicate.
 */
@Component
public class LocateService {

    private static final String SQL = """
            SELECT s.id, s.project_id, p.organization_id, s.slug, s.name, s.kind,
                   pl.node_id
              FROM service s
              JOIN project p ON p.id = s.project_id
              LEFT JOIN placement pl ON pl.service_id = s.id AND pl.state = 'ACTIVE'
             WHERE s.id = :serviceId
            """;

    private final JdbcClient jdbc;

    public LocateService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The service's place in the world.
     *
     * @throws NotFoundException if there is no such service
     */
    public ServiceLocation byId(UUID serviceId) {
        return optionally(serviceId)
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
    }

    /** The same question for a caller deciding whether to offer a link. */
    public Optional<ServiceLocation> optionally(UUID serviceId) {
        if (serviceId == null) {
            return Optional.empty();
        }
        return jdbc.sql(SQL)
                .param("serviceId", serviceId)
                .query(LocateService::map)
                .optional();
    }

    /**
     * The location, insisting a node is holding it.
     *
     * <p>For the callers whose next line is an RPC: a terminal, a file listing, a log
     * stream. Refusing here with a sentence that says why beats a
     * {@code NullPointerException} inside a gRPC stub, and "nothing is running this yet"
     * is a real state a customer can act on.
     *
     * @throws NotFoundException if there is no such service
     * @throws ServiceNotPlaced  if nothing is currently holding it
     */
    public ServiceLocation onANode(UUID serviceId) {
        ServiceLocation location = byId(serviceId);
        if (!location.isPlaced()) {
            throw new ServiceNotPlaced(serviceId, location.name());
        }
        return location;
    }

    private static ServiceLocation map(ResultSet row, int rowNumber) throws SQLException {
        return new ServiceLocation(
                row.getObject("id", UUID.class),
                row.getObject("project_id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getObject("node_id", UUID.class),
                row.getString("slug"),
                row.getString("name"),
                ServiceKind.valueOf(row.getString("kind")));
    }
}
