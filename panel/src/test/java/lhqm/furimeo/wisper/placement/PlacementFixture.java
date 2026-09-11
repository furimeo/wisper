package lhqm.furimeo.wisper.placement;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.util.unit.DataSize;

import lhqm.furimeo.wisper.node.Node;
import lhqm.furimeo.wisper.node.NodeSettings;
import lhqm.furimeo.wisper.service.BuildPreset;
import lhqm.furimeo.wisper.service.DesiredState;
import lhqm.furimeo.wisper.service.RestartPolicy;
import lhqm.furimeo.wisper.service.RuntimeIsolation;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceKind;

/**
 * Ready-made rows for the tests in this package.
 *
 * <p>{@code Service} has thirty-five components because the table has thirty-five columns,
 * and {@code PlacementSettings} has fifteen. Writing either out in six test classes would
 * mean six places to fix when a column is added, five of which would be fixed by counting
 * commas.
 *
 * <p>The defaults are the interesting ones: an app with a port and a health path, a site
 * with a build and neither, and a settings record holding the production defaults, so a
 * test that depends on one is depending on what actually ships.
 */
final class PlacementFixture {

    /** A plausible envelope, so no fixture holds a plaintext-shaped secret. */
    private static final String ENVELOPE = "v1.AAAAAAAAAAAAAAAA.AAAA";

    static final long MEGABYTE = 1024L * 1024L;
    static final long GIGABYTE = 1024L * MEGABYTE;

    private PlacementFixture() {
    }

    static PlacementSettings settings() {
        return new PlacementSettings(5, 2, DataSize.ofMegabytes(64), 3, Duration.ofHours(24),
                "upload-staging", Duration.ofSeconds(30), 65_536L,
                2000L, DataSize.ofGigabytes(2), 200,
                Duration.ofSeconds(10), 3, Duration.ofSeconds(20), "wisper-tenant-");
    }

    static NodeSettings nodeSettings(int cpuPercent, int memoryPercent, int diskPercent) {
        return new NodeSettings(Duration.ofSeconds(60), Duration.ofSeconds(20),
                Duration.ofMinutes(15), Duration.ofSeconds(15),
                cpuPercent, memoryPercent, diskPercent, "", Path.of("./var/dist"), "");
    }

    static Service app(UUID id, UUID projectId, DesiredState state, String... requiredTags) {
        return service(id, projectId, ServiceKind.APP, state, requiredTags);
    }

    static Service site(UUID id, UUID projectId, DesiredState state) {
        return service(id, projectId, ServiceKind.SITE, state);
    }

    /**
     * An app the customer has asked to be up.
     *
     * <p>Here so that a test about the spec never has to name {@code DesiredState}, whose
     * simple name belongs to two types - the panel's intent enum and the wire's - and would
     * otherwise force a fully-qualified name into every second line.
     */
    static Service runningApp(UUID id, UUID projectId) {
        return app(id, projectId, DesiredState.RUNNING);
    }

    /** A static site the customer has asked to be served. */
    static Service runningSite(UUID id, UUID projectId) {
        return site(id, projectId, DesiredState.RUNNING);
    }

    static Service service(UUID id, UUID projectId, ServiceKind kind, DesiredState state,
                           String... requiredTags) {
        boolean isApp = kind == ServiceKind.APP;
        return new Service(id, projectId, isApp ? "Api" : "Docs", isApp ? "api" : "docs", kind,
                state, RuntimeIsolation.RUNSC, null,
                isApp ? "node:22" : null, isApp ? "sha256:cafe" : null,
                isApp ? new String[] {"node", "server.js"} : new String[0],
                isApp ? new String[] {"docker-entrypoint.sh"} : new String[0],
                isApp ? "/srv" : null, isApp ? Integer.valueOf(8080) : null,
                isApp ? "/healthz" : null, 30, RestartPolicy.ALWAYS,
                isApp ? null : BuildPreset.NODE, null, isApp ? null : "dist", 5,
                null, null, null, true, ENVELOPE,
                500L, 256 * MEGABYTE, GIGABYTE, 256,
                requiredTags, null, Instant.EPOCH, Instant.EPOCH, 1L);
    }

    static Placement placement(UUID serviceId, UUID nodeId, PlacementState state, boolean pinned) {
        return new Placement(UUID.randomUUID(), serviceId, nodeId, state, pinned, "fixture",
                Instant.EPOCH, state == PlacementState.RELEASED ? Instant.EPOCH : null,
                null, null, null, null, 0, null, null, null,
                Instant.EPOCH, Instant.EPOCH, 1L);
    }

    static PlacedService placed(Service service, UUID nodeId, PlacementState state,
                                UUID organizationId, String releaseId) {
        return new PlacedService(placement(service.id(), nodeId, state, false), service,
                organizationId, releaseId, "");
    }

    /**
     * A candidate node with the headroom already applied. Memory and disk are large enough
     * never to be the constraint, so a test that says "nearly full" only has to talk about
     * one number.
     */
    static NodeCapacity capacity(UUID nodeId, String name, long cpuTotal, long cpuCommitted,
                                 int headroomPercent) {
        return NodeCapacity.of(nodeId, name,
                cpuTotal, cpuCommitted,
                64 * GIGABYTE, 0L,
                1024 * GIGABYTE, 0L,
                headroomPercent, headroomPercent, headroomPercent);
    }

    static Node enrolledNode(UUID id, String name) {
        return Node.created(id, name, "", "203.0.113.10", List.of())
                .enrolled("fingerprint-" + name, "public-key", "credential-hash",
                        "panel.example:9090", Instant.EPOCH);
    }

    static Node drainingNode(UUID id, String name) {
        return enrolledNode(id, name).draining(Instant.EPOCH);
    }
}
