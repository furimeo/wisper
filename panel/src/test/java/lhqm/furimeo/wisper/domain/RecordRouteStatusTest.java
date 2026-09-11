package lhqm.furimeo.wisper.domain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.proto.v1.RouteStatus;

/**
 * Which reports a node is allowed to make.
 *
 * <p>A node authenticates as itself and then names hostnames in the body of the message. The
 * set it may speak about is exactly the set its spec gave it; anything else would let one
 * machine write certificate records against another's domains.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecordRouteStatusTest {

    private static final UUID NODE = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID DOMAIN_ID = UUID.randomUUID();
    private static final Instant OBSERVED = Instant.parse("2026-03-01T12:00:00Z");

    private final Domain routed =
            DomainFixture.stored(DOMAIN_ID, SERVICE, "shop.example.com", DomainKind.PRIMARY);

    @Mock
    private DomainRepository domains;

    @Mock
    private RecordCertificateStatus certificates;

    @InjectMocks
    private RecordRouteStatus routes;

    @BeforeEach
    void oneHostnameIsRoutedToThisNode() {
        given(domains.findRoutedTo(NODE)).willReturn(List.of(routed));
    }

    @Test
    void aReportForARoutedHostnameReachesTheCertificateRecord() {
        routes.accept(NODE, List.of(status("shop.example.com")), OBSERVED);

        verify(certificates).accept(eq(NODE), eq(routed), any(), eq(OBSERVED));
    }

    @Test
    void aHostnameThisNodeWasNeverGivenIsIgnored() {
        // Otherwise a node could mark a competitor's hostname as failing, or claim an expiry
        // that silences a real warning.
        routes.accept(NODE, List.of(status("someone-elses.example.com")), OBSERVED);

        verify(certificates, never()).accept(any(), any(), any(), any());
    }

    @Test
    void oneStaleEntryDoesNotThrowAwayTheRestOfTheBatch() {
        // A report can legitimately arrive a second after a customer removed a hostname.
        routes.accept(NODE, List.of(status("gone.example.com"), status("shop.example.com")),
                OBSERVED);

        verify(certificates).accept(eq(NODE), eq(routed), any(), eq(OBSERVED));
    }

    @Test
    void theFullyQualifiedFormWithADotAndCapitalsIsTheSameHostname() {
        // Caddy hands back whatever the SNI carried. Matching byte for byte would silently
        // drop every report for the name.
        routes.accept(NODE, List.of(status("Shop.Example.COM.")), OBSERVED);

        verify(certificates).accept(eq(NODE), eq(routed), any(), eq(OBSERVED));
    }

    @Test
    void anEmptyBatchDoesNotEvenQueryTheRouteTable() {
        routes.accept(NODE, List.of(), OBSERVED);

        verifyNoInteractions(domains, certificates);
    }

    @Test
    void aNodeHoldingNothingMayReportOnNothing() {
        // A drained node still has an open stream and still reports. Its spec is empty, so
        // every hostname it could name belongs to somebody else.
        given(domains.findRoutedTo(NODE)).willReturn(List.of());

        routes.accept(NODE, List.of(status("shop.example.com")), OBSERVED);

        verify(certificates, never()).accept(any(), any(), any(), any());
    }

    private static RouteStatus status(String hostname) {
        return RouteStatus.newBuilder().setDomain(hostname).setServing(true).build();
    }
}
