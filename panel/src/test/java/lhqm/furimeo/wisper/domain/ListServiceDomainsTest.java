package lhqm.furimeo.wisper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The page's two derived answers: which certificate to show, and whether the edge is
 * actually answering for the hostname.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ListServiceDomainsTest {

    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID DOMAIN_ID = UUID.randomUUID();
    private static final String HOSTNAME = "shop.example.com";
    private static final String NODE_ADDRESS = "198.51.100.4";
    private static final Instant NOT_AFTER = Instant.parse("2026-05-30T12:00:00Z");

    @Mock
    private DomainRepository domains;

    @Mock
    private CertificateRepository certificates;

    @InjectMocks
    private ListServiceDomains listing;

    @BeforeEach
    void oneHostnameOnAPlacedService() {
        given(domains.findByServiceId(SERVICE)).willReturn(List.of(
                DomainFixture.stored(DOMAIN_ID, SERVICE, HOSTNAME, DomainKind.PRIMARY)));
        given(domains.findActiveNodeOf(SERVICE)).willReturn(Optional.of(NODE));
        given(domains.findActiveNodeAddressOf(SERVICE)).willReturn(Optional.of(NODE_ADDRESS));
    }

    @Test
    void aLiveCertificateFromTheNodeHoldingTheServiceMeansItIsServing() {
        given(certificates.findByDomainIdIn(List.of(DOMAIN_ID))).willReturn(List.of(
                DomainFixture.live(UUID.randomUUID(), DOMAIN_ID, NODE, HOSTNAME, NOT_AFTER)));

        DomainView view = listing.of(SERVICE).domains().get(0);

        assertThat(view.serving()).isTrue();
        assertThat(view.certificate()).isNotNull();
        assertThat(view.certificate().state()).isEqualTo(CertificateState.ISSUED);
        assertThat(view.challengeRecordName())
                .isEqualTo("_wisper-challenge.shop.example.com");
    }

    @Test
    void aCertificateFromSomeOtherNodeDoesNotMeanThisOneIsServing() {
        // The service moved. The old node's certificate proves nothing about the new one.
        given(certificates.findByDomainIdIn(List.of(DOMAIN_ID))).willReturn(List.of(
                DomainFixture.live(UUID.randomUUID(), DOMAIN_ID, UUID.randomUUID(), HOSTNAME,
                        NOT_AFTER)));

        assertThat(listing.of(SERVICE).domains().get(0).serving()).isFalse();
    }

    @Test
    void aServiceOnNoNodeIsNeverServing() {
        given(domains.findActiveNodeOf(SERVICE)).willReturn(Optional.empty());
        given(domains.findActiveNodeAddressOf(SERVICE)).willReturn(Optional.empty());
        given(certificates.findByDomainIdIn(List.of(DOMAIN_ID))).willReturn(List.of(
                DomainFixture.live(UUID.randomUUID(), DOMAIN_ID, NODE, HOSTNAME, NOT_AFTER)));

        ServiceDomains page = listing.of(SERVICE);

        assertThat(page.domains().get(0).serving()).isFalse();
        assertThat(page.nodeAddress()).isNull();
    }

    @Test
    void aHostnameWithTlsSwitchedOffIsServingAsSoonAsANodeHoldsIt() {
        // There is no certificate to wait for, so there is nothing else that can fail.
        given(domains.findByServiceId(SERVICE)).willReturn(List.of(DomainFixture.stored(
                DOMAIN_ID, SERVICE, HOSTNAME, DomainKind.PRIMARY, DomainTlsMode.OFF)));

        assertThat(listing.of(SERVICE).domains().get(0).serving()).isTrue();
    }

    @Test
    void aFailedAttemptIsShownWhenThereIsNothingLive() {
        // The error on the last attempt is the whole reason the customer opened the page.
        given(certificates.findByDomainIdIn(List.of(DOMAIN_ID)))
                .willReturn(List.of(failedAt(Instant.parse("2026-03-01T00:00:00Z"),
                        "rate limited")));

        DomainView view = listing.of(SERVICE).domains().get(0);

        assertThat(view.certificate().state()).isEqualTo(CertificateState.FAILED);
        assertThat(view.certificate().lastError()).isEqualTo("rate limited");
        assertThat(view.serving()).isFalse();
        assertThat(view.isAtRisk()).isTrue();
    }

    @Test
    void theLiveRowWinsOverAMoreRecentlyTouchedDeadOne() {
        Certificate live = DomainFixture.live(UUID.randomUUID(), DOMAIN_ID, NODE, HOSTNAME,
                NOT_AFTER);
        given(certificates.findByDomainIdIn(List.of(DOMAIN_ID))).willReturn(List.of(
                failedAt(Instant.parse("2030-01-01T00:00:00Z"), "later"), live));

        assertThat(listing.of(SERVICE).domains().get(0).certificate().state())
                .isEqualTo(CertificateState.ISSUED);
    }

    @Test
    void aHostnameNoNodeHasEverMentionedHasNoCertificateAtAll() {
        DomainView view = listing.of(SERVICE).domains().get(0);

        assertThat(view.certificate()).isNull();
        assertThat(view.serving()).isFalse();
        assertThat(view.isAtRisk()).isTrue();
    }

    @Test
    void aServiceWithNoHostnamesStillReportsWhereDnsWouldHaveToPoint() {
        given(domains.findByServiceId(SERVICE)).willReturn(List.of());

        ServiceDomains page = listing.of(SERVICE);

        assertThat(page.domains()).isEmpty();
        assertThat(page.nodeAddress()).isEqualTo(NODE_ADDRESS);
    }

    private static Certificate failedAt(Instant when, String error) {
        return new Certificate(UUID.randomUUID(), DOMAIN_ID, NODE, CertificateState.FAILED,
                null, null, null, new String[0], null, null, null, null, when, 1, error,
                when, when, 2L);
    }
}
