package lhqm.furimeo.wisper.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Ready-made {@link Service} rows for the tests in this package.
 *
 * <p>{@code Service} has thirty-five components because the table has thirty-five columns.
 * Writing that constructor out in five test classes would mean five places to fix when a
 * column is added, and four of them would be fixed by counting commas.
 *
 * <p>The two factories differ in exactly the ways the schema says the two kinds differ -
 * an app has an image and no build, a site has a build and neither image nor port - so a
 * test that passes with one and fails with the other is testing the divergence rather
 * than an accident of the fixture.
 */
final class ServiceFixture {

    /** A plausible envelope, so a fixture never contains a plaintext-shaped secret. */
    private static final String ENVELOPE = "v1.AAAAAAAAAAAAAAAA.AAAA";

    private ServiceFixture() {
    }

    static Service app(UUID id, UUID projectId) {
        return of(id, projectId, ServiceKind.APP, DesiredState.STOPPED, null);
    }

    static Service site(UUID id, UUID projectId) {
        return of(id, projectId, ServiceKind.SITE, DesiredState.STOPPED, null);
    }

    static Service running(UUID id, UUID projectId, ServiceKind kind) {
        return of(id, projectId, kind, DesiredState.RUNNING, null);
    }

    static Service archived(UUID id, UUID projectId, ServiceKind kind) {
        return of(id, projectId, kind, DesiredState.STOPPED, Instant.now());
    }

    static Service of(UUID id, UUID projectId, ServiceKind kind, DesiredState state,
                      Instant archivedAt) {
        boolean isApp = kind == ServiceKind.APP;
        return new Service(id, projectId, "Api", "api", kind, state, RuntimeIsolation.RUNSC, null,
                isApp ? "node:22" : null, null, new String[0], new String[0], null,
                isApp ? Integer.valueOf(8080) : null, null, 30, RestartPolicy.ALWAYS,
                isApp ? null : BuildPreset.NODE, null, isApp ? null : "dist", 5,
                null, null, null, true, ENVELOPE,
                500, 268_435_456L, 1_073_741_824L, 256,
                new String[0], archivedAt, Instant.now(), Instant.now(), 1L);
    }
}
