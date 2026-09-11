package lhqm.furimeo.wisper.domain;

import java.time.Instant;
import java.util.UUID;

import lhqm.furimeo.wisper.service.BuildPreset;
import lhqm.furimeo.wisper.service.DesiredState;
import lhqm.furimeo.wisper.service.RestartPolicy;
import lhqm.furimeo.wisper.service.RuntimeIsolation;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceKind;

/**
 * The rows these tests need, built once.
 *
 * <p>{@code Service} has thirty-five components and none of them matters to a domain test
 * beyond the id, the slug and whether it is archived. Spelling the constructor out in six
 * test classes would make each of them a page longer and none of them clearer.
 */
final class DomainFixture {

    private DomainFixture() {
    }

    /** A running app, not archived. */
    static Service service(UUID id, UUID projectId, String slug) {
        return service(id, projectId, slug, null);
    }

    static Service archivedService(UUID id, UUID projectId, String slug) {
        return service(id, projectId, slug, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static Service service(UUID id, UUID projectId, String slug, Instant archivedAt) {
        return new Service(id, projectId, slug, slug, ServiceKind.APP, DesiredState.RUNNING,
                RuntimeIsolation.RUNSC, null,
                "nginx:1.27", null, new String[0], new String[0], null, 8080, null, 30,
                RestartPolicy.ALWAYS,
                BuildPreset.STATIC, null, null, 5,
                null, null, null, false, null,
                500, 268_435_456L, 1_073_741_824L, 256,
                new String[0], archivedAt, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), 1L);
    }

    /** A stored, unverified hostname with a row version, so it reads as persisted. */
    static Domain stored(UUID id, UUID serviceId, String hostname, DomainKind kind) {
        return stored(id, serviceId, hostname, kind, DomainTlsMode.ON_DEMAND);
    }

    static Domain stored(UUID id, UUID serviceId, String hostname, DomainKind kind,
                         DomainTlsMode tlsMode) {
        return new Domain(id, serviceId, hostname, kind, tlsMode,
                DomainVerification.PENDING, "tok-" + hostname, null, null, null,
                null, true, null,
                Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"), 1L);
    }

    /** A hostname that has already been proven. */
    static Domain verified(UUID id, UUID serviceId, String hostname) {
        return stored(id, serviceId, hostname, DomainKind.PRIMARY)
                .verified(Instant.parse("2026-02-02T00:00:00Z"));
    }

    /** A live certificate a node has reported. */
    static Certificate live(UUID id, UUID domainId, UUID nodeId, String hostname,
                            Instant notAfter) {
        Instant issuedAt = notAfter.minusSeconds(60 * 60 * 24 * 30);
        return new Certificate(id, domainId, nodeId, CertificateState.ISSUED, null, null,
                hostname, new String[] {hostname}, null, issuedAt, notAfter, issuedAt, null, 0,
                null, issuedAt, issuedAt, 3L);
    }
}
