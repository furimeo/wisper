package lhqm.furimeo.wisper.placement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.proto.v1.Route;
import lhqm.furimeo.wisper.proto.v1.TlsMode;

/**
 * The hostnames this node has to answer for.
 *
 * <p>Customers' traffic never touches the panel: the node has the public address, the
 * embedded Caddy listens on 80 and 443, and its on-demand-TLS {@code ask} handler answers
 * from the route table in this spec, in-process. That is what makes a site keep working -
 * and keep renewing certificates - while the panel is down (design §5.4), and it is why the
 * routes are part of the desired state rather than something the panel pushes separately.
 *
 * <p>Ordered by hostname, which is globally unique, so the same domains always produce the
 * same document.
 */
@Component
public class BuildRoutes {

    private static final String SQL = """
            SELECT service_id, hostname, tls_mode, force_https, target_port, verification_state
              FROM domain
             WHERE service_id IN (:serviceIds)
             ORDER BY hostname
            """;

    private final JdbcClient jdbc;

    public BuildRoutes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One route per hostname pointing at a service this node holds. */
    @Transactional(readOnly = true)
    public List<Route> from(List<PlacedService> placed) {
        if (placed.isEmpty()) {
            return List.of();
        }
        Map<UUID, PlacedService> byService = new HashMap<>();
        for (PlacedService entry : placed) {
            byService.put(entry.service().id(), entry);
        }

        List<HostRow> rows = jdbc.sql(SQL)
                .param("serviceIds", List.copyOf(byService.keySet()))
                .query(BuildRoutes::map)
                .list();

        List<Route> routes = new ArrayList<>(rows.size());
        for (HostRow row : rows) {
            PlacedService target = byService.get(row.serviceId());
            if (target == null) {
                continue;
            }
            routes.add(routeFor(row, target));
        }
        return List.copyOf(routes);
    }

    /**
     * One {@code domain} row as the edge has to see it.
     *
     * <p>Separate from the query, and package-private, because every rule node-spec.md §3.6
     * states is in here and none of them needs a database to check: which port the edge
     * proxies to, which of the schema's three TLS modes the wire's two can carry, and when a
     * redirect to HTTPS would send a visitor at a handshake that cannot succeed.
     */
    static Route routeFor(HostRow row, PlacedService target) {
        TlsMode tls = tlsModeOf(row.tlsMode());
        return Route.newBuilder()
                .setDomain(row.hostname())
                .setWorkloadId(target.workloadId())
                .setPort(portOf(row, target))
                .setTlsMode(tls)
                .setForceHttps(forcesHttps(row, tls))
                .build();
    }

    /**
     * Which port the edge proxies to.
     *
     * <p>The domain's own override wins, then the service's port. A site has neither and
     * needs neither: there is no process behind it and the edge serves the release
     * directory from disk, so the field is ignored.
     */
    private static int portOf(HostRow row, PlacedService target) {
        if (row.targetPort() != null) {
            return row.targetPort();
        }
        Integer port = target.service().containerPort();
        return port == null ? 0 : port;
    }

    /**
     * The two modes the wire has, from the three the schema allows.
     *
     * <p>{@code OFF} is plain HTTP, for a hostname whose DNS still points somewhere else:
     * the customer can check the site before cutting over, and ACME is not failing at them
     * in the meantime. {@code STATIC} maps onto on-demand issuance because in v1 the node
     * owns every certificate and there is nowhere to upload one - {@code certificate} holds
     * metadata and, deliberately, no private key.
     */
    private static TlsMode tlsModeOf(String stored) {
        return "OFF".equals(stored) ? TlsMode.TLS_MODE_DISABLED : TlsMode.TLS_MODE_ON_DEMAND;
    }

    /**
     * Whether plain HTTP is redirected to HTTPS.
     *
     * <p>Off while TLS is off, and off while the hostname is still being validated. A
     * domain whose DNS has not arrived yet cannot get a certificate, and redirecting it to
     * a port that will fail the handshake turns "not set up yet" into "broken".
     */
    private static boolean forcesHttps(HostRow row, TlsMode tls) {
        return row.forceHttps()
                && tls == TlsMode.TLS_MODE_ON_DEMAND
                && "VERIFIED".equals(row.verificationState());
    }

    private static HostRow map(ResultSet row, int rowNumber) throws SQLException {
        // getObject with a boxed type rather than getInt plus wasNull: wasNull refers to
        // the last column read, and whichever column that is becomes an ordering trap the
        // next time somebody adds a field to this mapper.
        return new HostRow(
                row.getObject("service_id", UUID.class),
                row.getString("hostname"),
                row.getString("tls_mode"),
                row.getBoolean("force_https"),
                row.getObject("target_port", Integer.class),
                row.getString("verification_state"));
    }

    /** One {@code domain} row, narrowed to what a route needs. */
    record HostRow(UUID serviceId, String hostname, String tlsMode, boolean forceHttps,
                   Integer targetPort, String verificationState) {
    }
}
