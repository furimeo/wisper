package lhqm.furimeo.wisper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.google.protobuf.Timestamp;

import lhqm.furimeo.wisper.proto.v1.RouteStatus;

/**
 * Writing back what a node said about a certificate, and not writing back what it did not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecordCertificateStatusTest {

    private static final UUID NODE = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID DOMAIN_ID = UUID.randomUUID();
    private static final String HOSTNAME = "shop.example.com";
    private static final Instant OBSERVED = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant NOT_AFTER = Instant.parse("2026-05-30T12:00:00Z");

    private final Domain domain =
            DomainFixture.stored(DOMAIN_ID, SERVICE, HOSTNAME, DomainKind.PRIMARY);

    @Mock
    private CertificateRepository certificates;

    @InjectMocks
    private RecordCertificateStatus statuses;

    @BeforeEach
    void nothingHasBeenReportedYet() {
        given(certificates.findLiveFor(DOMAIN_ID)).willReturn(Optional.empty());
        given(certificates.findNewestFor(DOMAIN_ID)).willReturn(Optional.empty());
        given(certificates.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void aValidReportBecomesAnIssuedCertificateWithBothValidityBounds() {
        statuses.accept(NODE, domain, valid(NOT_AFTER), OBSERVED);

        Certificate written = saved();
        assertThat(written.state()).isEqualTo(CertificateState.ISSUED);
        // certificate_issued_has_validity needs both, and the CHECK would reject the row
        // rather than the panel noticing later that an expiry warning has nothing to read.
        assertThat(written.notBefore()).isNotNull();
        assertThat(written.notAfter()).isEqualTo(NOT_AFTER);
        assertThat(written.notBefore()).isBefore(written.notAfter());
        assertThat(written.subjectCommonName()).isEqualTo(HOSTNAME);
        assertThat(written.nodeId()).isEqualTo(NODE);
        assertThat(written.renewalFailureCount()).isZero();
    }

    @Test
    void theSameReportArrivingAgainWritesNothing() {
        // A node reports every route on every reconcile pass. Writing each one would bump
        // version and updated_at for the whole fleet every fifteen seconds.
        given(certificates.findLiveFor(DOMAIN_ID)).willReturn(Optional.of(
                DomainFixture.live(UUID.randomUUID(), DOMAIN_ID, NODE, HOSTNAME, NOT_AFTER)));

        statuses.accept(NODE, domain, valid(NOT_AFTER), OBSERVED);

        verify(certificates, never()).save(any());
    }

    @Test
    void aRenewalWithANewExpiryIsWritten() {
        given(certificates.findLiveFor(DOMAIN_ID)).willReturn(Optional.of(
                DomainFixture.live(UUID.randomUUID(), DOMAIN_ID, NODE, HOSTNAME, NOT_AFTER)));

        Instant later = NOT_AFTER.plusSeconds(60 * 60 * 24 * 90);
        statuses.accept(NODE, domain, valid(later), OBSERVED);

        assertThat(saved().notAfter()).isEqualTo(later);
        assertThat(saved().notBefore()).isEqualTo(OBSERVED);
    }

    @Test
    void aValidReportWithNoExpiryChangesNothingRatherThanInventingOne() {
        // certificate_validity_ordered cannot be satisfied from this, and a fabricated
        // expiry is what a customer would plan their renewals from.
        statuses.accept(NODE, domain, RouteStatus.newBuilder().setDomain(HOSTNAME)
                .setCertificate(lhqm.furimeo.wisper.proto.v1.CertificateState
                        .CERTIFICATE_STATE_VALID)
                .build(), OBSERVED);

        verify(certificates, never()).save(any());
    }

    @Test
    void aFailureAgainstALiveCertificateLeavesItLive() {
        // The old certificate is still what visitors are being served. Moving the row to
        // FAILED would throw away the expiry the "renewal is failing" warning reads.
        given(certificates.findLiveFor(DOMAIN_ID)).willReturn(Optional.of(
                DomainFixture.live(UUID.randomUUID(), DOMAIN_ID, NODE, HOSTNAME, NOT_AFTER)));

        statuses.accept(NODE, domain, failed("rate limited by the CA"), OBSERVED);

        Certificate written = saved();
        assertThat(written.state()).isEqualTo(CertificateState.RENEWING);
        assertThat(written.isLive()).isTrue();
        assertThat(written.notAfter()).isEqualTo(NOT_AFTER);
        assertThat(written.lastError()).isEqualTo("rate limited by the CA");
        assertThat(written.renewalFailureCount()).isEqualTo(1);
    }

    @Test
    void aFirstFailureWithNoCertificateBehindItIsRecordedAsFailed() {
        statuses.accept(NODE, domain, failed("DNS problem: NXDOMAIN"), OBSERVED);

        Certificate written = saved();
        assertThat(written.state()).isEqualTo(CertificateState.FAILED);
        assertThat(written.lastError()).contains("NXDOMAIN");
        assertThat(written.renewalFailureCount()).isEqualTo(1);
    }

    @Test
    void theSameFailureRepeatedDoesNotRunTheCounterAway() {
        Certificate alreadyFailed = new Certificate(UUID.randomUUID(), DOMAIN_ID, NODE,
                CertificateState.FAILED, null, null, null, new String[0], null, null, null,
                null, OBSERVED, 1, "DNS problem: NXDOMAIN", OBSERVED, OBSERVED, 2L);
        given(certificates.findNewestFor(DOMAIN_ID)).willReturn(Optional.of(alreadyFailed));

        statuses.accept(NODE, domain, failed("DNS problem: NXDOMAIN"), OBSERVED);

        verify(certificates, never()).save(any());
    }

    @Test
    void aHostnameWithNoCertificateAtAllProducesNoRow() {
        // TLS switched off, or the node has not started. Either way there is nothing to say,
        // and a node that stops mentioning a certificate has not revoked it.
        statuses.accept(NODE, domain, RouteStatus.newBuilder().setDomain(HOSTNAME)
                .setCertificate(lhqm.furimeo.wisper.proto.v1.CertificateState
                        .CERTIFICATE_STATE_NONE)
                .build(), OBSERVED);

        verify(certificates, never()).save(any());
    }

    @Test
    void issuanceUnderWayOnAHostnameWithNoCertificateIsPending() {
        statuses.accept(NODE, domain, RouteStatus.newBuilder().setDomain(HOSTNAME)
                .setCertificate(lhqm.furimeo.wisper.proto.v1.CertificateState
                        .CERTIFICATE_STATE_ISSUING)
                .build(), OBSERVED);

        Certificate written = saved();
        assertThat(written.state()).isEqualTo(CertificateState.PENDING);
        assertThat(written.lastRenewalAttemptAt()).isEqualTo(OBSERVED);
    }

    @Test
    void issuanceUnderWayOnALiveCertificateIsARenewal() {
        given(certificates.findLiveFor(DOMAIN_ID)).willReturn(Optional.of(
                DomainFixture.live(UUID.randomUUID(), DOMAIN_ID, NODE, HOSTNAME, NOT_AFTER)));

        statuses.accept(NODE, domain, RouteStatus.newBuilder().setDomain(HOSTNAME)
                .setCertificate(lhqm.furimeo.wisper.proto.v1.CertificateState
                        .CERTIFICATE_STATE_ISSUING)
                .build(), OBSERVED);

        Certificate written = saved();
        assertThat(written.state()).isEqualTo(CertificateState.RENEWING);
        assertThat(written.notAfter()).isEqualTo(NOT_AFTER);
    }

    @Test
    void aRevokedRowIsNeverRevived() {
        Certificate revoked = new Certificate(UUID.randomUUID(), DOMAIN_ID, NODE,
                CertificateState.REVOKED, null, null, null, new String[0], null, null, null,
                null, null, 0, null, OBSERVED, OBSERVED, 4L);
        given(certificates.findNewestFor(DOMAIN_ID)).willReturn(Optional.of(revoked));

        statuses.accept(NODE, domain, valid(NOT_AFTER), OBSERVED);

        assertThat(saved().id()).isNotEqualTo(revoked.id());
        assertThat(saved().version()).isNull();
    }

    private static RouteStatus valid(Instant notAfter) {
        return RouteStatus.newBuilder()
                .setDomain(HOSTNAME)
                .setServing(true)
                .setCertificate(lhqm.furimeo.wisper.proto.v1.CertificateState
                        .CERTIFICATE_STATE_VALID)
                .setCertificateNotAfter(Timestamp.newBuilder()
                        .setSeconds(notAfter.getEpochSecond()).build())
                .build();
    }

    private static RouteStatus failed(String error) {
        return RouteStatus.newBuilder()
                .setDomain(HOSTNAME)
                .setServing(false)
                .setCertificate(lhqm.furimeo.wisper.proto.v1.CertificateState
                        .CERTIFICATE_STATE_FAILED)
                .setLastError(error)
                .build();
    }

    private Certificate saved() {
        ArgumentCaptor<Certificate> captor = ArgumentCaptor.forClass(Certificate.class);
        verify(certificates).save(captor.capture());
        return captor.getValue();
    }
}
