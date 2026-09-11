package lhqm.furimeo.wisper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.service.LocateService;
import lhqm.furimeo.wisper.service.ServiceKind;
import lhqm.furimeo.wisper.service.ServiceLocation;

/**
 * The check that stops one customer taking another's hostname.
 *
 * <p>The three cases that matter are the ones this class opens with: a name pointing
 * somewhere else is refused, a name pointing here is accepted, and a resolver that could not
 * be reached changes nothing. Getting the third one wrong would mark every hostname on the
 * platform as failing the moment the panel's own network hiccuped - and {@code force_https}
 * follows verification, so it would switch HTTPS redirects off across every site at once.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VerifyDomainOwnershipTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID DOMAIN = UUID.randomUUID();
    private static final String HOSTNAME = "shop.example.com";
    private static final String NODE_ADDRESS = "198.51.100.4";

    @Mock
    private DomainRepository domains;

    @Mock
    private DnsLookup dns;

    @Mock
    private LocateService services;

    @Mock
    private PublishNodeSpec specs;

    @Mock
    private AuditTrail audit;

    private final DomainSettings settings = new DomainSettings(Duration.ofSeconds(2), 2,
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofMinutes(15), Duration.ofHours(6));

    private VerifyDomainOwnership verification;

    @BeforeEach
    void aPlacedServiceWithOneUnverifiedHostname() {
        verification = new VerifyDomainOwnership(domains, dns, settings, services, specs, audit);
        given(domains.findById(DOMAIN)).willReturn(Optional.of(
                DomainFixture.stored(DOMAIN, SERVICE, HOSTNAME, DomainKind.PRIMARY)));
        given(domains.findNodeAddress(DOMAIN)).willReturn(Optional.of(NODE_ADDRESS));
        given(domains.save(any())).willAnswer(call -> call.getArgument(0));
        given(services.byId(SERVICE)).willReturn(new ServiceLocation(SERVICE, PROJECT,
                ORGANIZATION, NODE, "shop", "Shop", ServiceKind.APP));
        // dns is left unstubbed on purpose: Mockito answers a List-returning method with an
        // empty list, which is exactly "the resolver answered and there is nothing there".
    }

    @Test
    void aHostnamePointingSomewhereElseFailsAndTheMessageNamesBothAddresses() {
        given(dns.addresses(HOSTNAME)).willReturn(List.of("203.0.113.9"));

        VerificationResult result = verification.check(DOMAIN);

        assertThat(result.state()).isEqualTo(DomainVerification.FAILED);
        assertThat(result.detail()).contains("203.0.113.9").contains(NODE_ADDRESS);
        assertThat(saved().verificationState()).isEqualTo(DomainVerification.FAILED);
        assertThat(saved().verifiedAt()).isNull();
        verifyNoInteractions(specs);
    }

    @Test
    void aHostnameThatDoesNotResolveAtAllIsAlsoAFailedCheck() {
        given(dns.addresses(HOSTNAME)).willReturn(List.of());

        VerificationResult result = verification.check(DOMAIN);

        assertThat(result.state()).isEqualTo(DomainVerification.FAILED);
        assertThat(result.detail()).contains("does not resolve").contains(NODE_ADDRESS);
    }

    @Test
    void aHostnamePointingAtTheNodeIsVerifiedAndTheSpecIsRepublished() {
        given(dns.addresses(HOSTNAME)).willReturn(List.of(NODE_ADDRESS));

        VerificationResult result = verification.check(DOMAIN);

        assertThat(result.isVerified()).isTrue();
        assertThat(result.shouldRecheck()).isFalse();
        assertThat(saved().verifiedAt()).isNotNull();
        // force_https in the route table is off until a hostname is proven, so proving one
        // changes the node's desired state.
        verify(specs).forService(eq(SERVICE), anyString());
    }

    @Test
    void oneMatchingAddressAmongSeveralIsEnough() {
        given(dns.addresses(HOSTNAME)).willReturn(List.of("203.0.113.9", NODE_ADDRESS));

        assertThat(verification.check(DOMAIN).isVerified()).isTrue();
    }

    @Test
    void theChallengeRecordVerifiesAHostnameStillPointingAtItsOldHost() {
        // The migration case: the name is serving a live site elsewhere and cannot be
        // repointed until the replacement is ready.
        given(dns.addresses(HOSTNAME)).willReturn(List.of("203.0.113.9"));
        given(dns.textRecords(VerificationToken.recordName(HOSTNAME)))
                .willReturn(List.of("v=spf1 -all",
                        VerificationToken.recordValue("tok-" + HOSTNAME)));

        VerificationResult result = verification.check(DOMAIN);

        assertThat(result.isVerified()).isTrue();
        assertThat(result.detail()).contains(VerificationToken.LABEL);
    }

    @Test
    void theChallengeIsAlsoAcceptedOneLevelUp() {
        given(dns.addresses(HOSTNAME)).willReturn(List.of());
        given(dns.textRecords(VerificationToken.parentRecordName(HOSTNAME)))
                .willReturn(List.of(VerificationToken.recordValue("tok-" + HOSTNAME)));

        assertThat(verification.check(DOMAIN).isVerified()).isTrue();
    }

    @Test
    void somebodyElsesChallengeRecordProvesNothing() {
        given(dns.addresses(HOSTNAME)).willReturn(List.of("203.0.113.9"));
        given(dns.textRecords(anyString()))
                .willReturn(List.of(VerificationToken.recordValue("some-other-token")));

        assertThat(verification.check(DOMAIN).state()).isEqualTo(DomainVerification.FAILED);
    }

    @Test
    void aResolverThatCannotBeReachedIsNotAnAnswer() {
        // "I could not ask" is not "the answer is no".
        willThrow(new DnsUnavailable(HOSTNAME, "timed out", null))
                .given(dns).addresses(HOSTNAME);

        VerificationResult result = verification.check(DOMAIN);

        assertThat(result.state()).isEqualTo(DomainVerification.PENDING);
        assertThat(result.state()).isNotEqualTo(DomainVerification.FAILED);
        assertThat(result.shouldRecheck()).isTrue();
        assertThat(saved().verificationState()).isEqualTo(DomainVerification.PENDING);
    }

    @Test
    void aServiceOnNoNodeYetIsPendingRatherThanTheCustomersFault() {
        given(services.byId(SERVICE)).willReturn(new ServiceLocation(SERVICE, PROJECT,
                ORGANIZATION, null, "shop", "Shop", ServiceKind.APP));

        VerificationResult result = verification.check(DOMAIN);

        assertThat(result.state()).isEqualTo(DomainVerification.PENDING);
        assertThat(result.detail()).contains("not running on a node");
        verifyNoInteractions(dns);
    }

    @Test
    void aNodeWithNoPublicAddressGivesNothingToCompareAgainst() {
        given(domains.findNodeAddress(DOMAIN)).willReturn(Optional.of("  "));

        VerificationResult result = verification.check(DOMAIN);

        assertThat(result.state()).isEqualTo(DomainVerification.PENDING);
        assertThat(result.detail()).contains("no public address");
        verifyNoInteractions(dns);
    }

    @Test
    void aNodeNamedRatherThanNumberedIsResolvedBeforeItIsCompared() {
        given(domains.findNodeAddress(DOMAIN)).willReturn(Optional.of("node1.wisper.internal"));
        given(dns.addresses("node1.wisper.internal")).willReturn(List.of(NODE_ADDRESS));
        given(dns.addresses(HOSTNAME)).willReturn(List.of(NODE_ADDRESS));

        assertThat(verification.check(DOMAIN).isVerified()).isTrue();
    }

    @Test
    void anIpv6AddressWrittenTwoWaysIsOneAddress() {
        given(domains.findNodeAddress(DOMAIN)).willReturn(Optional.of("2001:db8::1"));
        given(dns.addresses(HOSTNAME)).willReturn(List.of("2001:db8:0:0:0:0:0:1"));

        assertThat(verification.check(DOMAIN).isVerified()).isTrue();
    }

    @Test
    void aVerifiedHostnameIsNeverLookedUpAgain() {
        // Verification is sticky: DNS moving during a migration must not switch HTTPS off
        // underneath a working site.
        given(domains.findById(DOMAIN))
                .willReturn(Optional.of(DomainFixture.verified(DOMAIN, SERVICE, HOSTNAME)));

        VerificationResult result = verification.check(DOMAIN);

        assertThat(result.isVerified()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.shouldRecheck()).isFalse();
        verifyNoInteractions(dns);
        verify(domains, never()).save(any());
    }

    @Test
    void aHostnameRemovedWhileTheCheckWasQueuedIsNotAnError() {
        given(domains.findById(DOMAIN)).willReturn(Optional.empty());

        VerificationResult result = verification.check(DOMAIN);

        assertThat(result.shouldRecheck()).isFalse();
        verify(domains, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void theAutomaticCheckOnlyAuditsWhenSomethingMoved() {
        given(dns.addresses(HOSTNAME)).willReturn(List.of(NODE_ADDRESS));

        verification.check(DOMAIN);

        verify(audit).record(any());
    }

    @Test
    void anAutomaticCheckThatFoundTheSameThingAgainWritesNoAuditLine() {
        // A recheck loop over every unverified hostname would otherwise bury the trail it
        // is part of.
        given(domains.findById(DOMAIN)).willReturn(Optional.of(
                DomainFixture.stored(DOMAIN, SERVICE, HOSTNAME, DomainKind.PRIMARY)
                        .failedCheck(Instant.parse("2026-02-03T00:00:00Z"), "nope")));
        given(dns.addresses(HOSTNAME)).willReturn(List.of("203.0.113.9"));

        verification.check(DOMAIN);

        verifyNoInteractions(audit);
    }

    private Domain saved() {
        ArgumentCaptor<Domain> captor = ArgumentCaptor.forClass(Domain.class);
        verify(domains).save(captor.capture());
        return captor.getValue();
    }
}
