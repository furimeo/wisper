package lhqm.furimeo.wisper.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import lhqm.furimeo.wisper.proto.v1.Route;
import lhqm.furimeo.wisper.proto.v1.TlsMode;
import lhqm.furimeo.wisper.service.Service;

/**
 * How a {@code domain} row becomes a route the embedded Caddy can serve (node-spec.md §3.6).
 *
 * <p>No {@code JdbcClient}: every rule here is a decision about one row and its service, and
 * that is why {@link BuildRoutes#routeFor} is a method of its own. Each of them is a way of
 * getting a customer's site quietly wrong rather than loudly broken - a redirect to a port
 * with no certificate, a proxy to the wrong port, a hostname served plaintext - so each one
 * is stated here.
 */
class BuildRoutesTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID ORGANIZATION = UUID.randomUUID();

    private final Service app = PlacementFixture.runningApp(UUID.randomUUID(), PROJECT);
    private final Service site = PlacementFixture.runningSite(UUID.randomUUID(), PROJECT);

    @Test
    void aVerifiedOnDemandHostnameGetsTlsAndTheRedirectItAskedFor() {
        Route route = routeFor(app, "api.example.com", "ON_DEMAND", true, null, "VERIFIED");

        assertThat(route.getDomain()).isEqualTo("api.example.com");
        assertThat(route.getWorkloadId()).isEqualTo(app.id().toString());
        assertThat(route.getTlsMode()).isEqualTo(TlsMode.TLS_MODE_ON_DEMAND);
        assertThat(route.getForceHttps()).isTrue();
        // The service's own container port, because the domain named none.
        assertThat(route.getPort()).isEqualTo(8080);
    }

    @Test
    void staticCollapsesOntoOnDemandBecauseTheNodeOwnsEveryCertificate() {
        // In v1 there is nowhere to upload a certificate: `certificate` holds metadata and,
        // deliberately, no private key. Sending TLS_MODE_UNSPECIFIED instead would leave the
        // node guessing about a hostname the customer asked to be encrypted.
        assertThat(routeFor(app, "www.example.com", "STATIC", true, null, "VERIFIED")
                .getTlsMode()).isEqualTo(TlsMode.TLS_MODE_ON_DEMAND);
    }

    @Test
    void tlsOffIsPlainHttpAndNeverRedirects() {
        Route route = routeFor(app, "cutover.example.com", "OFF", true, null, "VERIFIED");

        assertThat(route.getTlsMode()).isEqualTo(TlsMode.TLS_MODE_DISABLED);
        // The customer turned TLS off to check the site before pointing DNS at it.
        // Redirecting to a port that is not listening would undo exactly that.
        assertThat(route.getForceHttps()).isFalse();
    }

    @Test
    void anUnverifiedHostnameIsServedButNotRedirected() {
        // No DNS here yet means no certificate, and a redirect to a handshake that fails
        // turns "not set up yet" into "broken".
        assertThat(routeFor(app, "new.example.com", "ON_DEMAND", true, null, "PENDING")
                .getForceHttps()).isFalse();
        assertThat(routeFor(app, "bad.example.com", "ON_DEMAND", true, null, "FAILED")
                .getForceHttps()).isFalse();
    }

    @Test
    void aCustomerWhoTurnedTheRedirectOffKeepsItOff() {
        assertThat(routeFor(app, "api.example.com", "ON_DEMAND", false, null, "VERIFIED")
                .getForceHttps()).isFalse();
    }

    @Test
    void theDomainsOwnPortWinsOverTheServices() {
        assertThat(routeFor(app, "admin.example.com", "ON_DEMAND", true, 9000, "VERIFIED")
                .getPort()).isEqualTo(9000);
    }

    @Test
    void aSiteHasNoPortAndNeedsNone() {
        // There is no process behind a static site; the edge serves the release directory
        // from disk and ignores the field.
        assertThat(routeFor(site, "docs.example.com", "ON_DEMAND", true, null, "VERIFIED")
                .getPort()).isZero();
    }

    private Route routeFor(Service service, String hostname, String tlsMode, boolean forceHttps,
                           Integer targetPort, String verificationState) {
        return BuildRoutes.routeFor(
                new BuildRoutes.HostRow(service.id(), hostname, tlsMode, forceHttps, targetPort,
                        verificationState),
                PlacementFixture.placed(service, NODE, PlacementState.ACTIVE, ORGANIZATION, ""));
    }
}
