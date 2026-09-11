package lhqm.furimeo.wisper.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
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

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Who may invite whom, and what an invitation does before it is accepted - which is
 * nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InviteMemberTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID CALLER_ACCOUNT = UUID.randomUUID();
    private static final UUID INVITEE_ACCOUNT = UUID.randomUUID();
    private static final String EMAIL = "ada@example.com";

    @Mock
    private MemberRepository members;

    @Mock
    private FindAccount accounts;

    @Mock
    private QuotaGuard quotas;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private InviteMember inviteMember;

    private final AuditActor actor = AuditActor.system("test");

    @BeforeEach
    void anActiveAccountExistsAndIsNotAMemberYet() {
        given(accounts.byEmail(EMAIL)).willReturn(Optional.of(
                new AccountRef(INVITEE_ACCOUNT, EMAIL, "Ada", true)));
        given(members.findByOrganizationIdAndAccountId(ORGANIZATION, INVITEE_ACCOUNT))
                .willReturn(Optional.empty());
        given(members.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void anAdministratorInvitesADeveloperAndTheRowIsNotYetAccepted() {
        Member invitation = inviteMember.invite(actor, caller(MemberRole.ADMIN), EMAIL,
                MemberRole.DEVELOPER);

        assertThat(invitation.role()).isEqualTo(MemberRole.DEVELOPER);
        assertThat(invitation.isAccepted()).isFalse();
        assertThat(invitation.acceptedAt()).isNull();
        assertThat(invitation.invitedByAccountId()).isEqualTo(CALLER_ACCOUNT);
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(invitation::membership);
        verify(audit).record(any());
    }

    @Test
    void aDeveloperMayNotInvite() {
        assertThatExceptionOfType(PermissionDenied.class).isThrownBy(
                () -> inviteMember.invite(actor, caller(MemberRole.DEVELOPER), EMAIL,
                        MemberRole.VIEWER));
        verify(members, never()).save(any());
    }

    @Test
    void anAdministratorMayNotInviteAnOwner() {
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> inviteMember.invite(actor, caller(MemberRole.ADMIN), EMAIL,
                        MemberRole.OWNER))
                .withMessageContaining("an owner");
        verify(members, never()).save(any());
    }

    @Test
    void anOwnerMayInviteAnotherOwner() {
        Member invitation = inviteMember.invite(actor, caller(MemberRole.OWNER), EMAIL,
                MemberRole.OWNER);

        assertThat(invitation.role()).isEqualTo(MemberRole.OWNER);
    }

    @Test
    void theSeatIsClaimedBeforeTheAddressIsEvenLookedUp() {
        willThrow(new QuotaExceeded(ORGANIZATION, QuotaResource.MEMBER, 1,
                new QuotaAllowance(QuotaResource.MEMBER, 3, 3, QuotaAllowance.QuotaSource.PLAN)))
                .given(quotas).require(ORGANIZATION, QuotaResource.MEMBER, 1);

        assertThatExceptionOfType(QuotaExceeded.class).isThrownBy(
                () -> inviteMember.invite(actor, caller(MemberRole.OWNER), EMAIL,
                        MemberRole.VIEWER));
        verify(members, never()).save(any());
    }

    @Test
    void anUnknownAddressIsRefusedWithAnActionableMessage() {
        given(accounts.byEmail(EMAIL)).willReturn(Optional.empty());

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> inviteMember.invite(actor, caller(MemberRole.OWNER), EMAIL,
                        MemberRole.VIEWER))
                .withMessageContaining("create an account first")
                .matches(rejected -> "email".equals(rejected.field()));
    }

    @Test
    void aSuspendedAccountIsNotInvited() {
        given(accounts.byEmail(EMAIL)).willReturn(Optional.of(
                new AccountRef(INVITEE_ACCOUNT, EMAIL, "Ada", false)));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> inviteMember.invite(actor, caller(MemberRole.OWNER), EMAIL,
                        MemberRole.VIEWER))
                .withMessageContaining("suspended");
    }

    @Test
    void somebodyAlreadyInsideIsToldSoRatherThanHittingTheUniqueIndex() {
        given(members.findByOrganizationIdAndAccountId(ORGANIZATION, INVITEE_ACCOUNT))
                .willReturn(Optional.of(new Member(UUID.randomUUID(), ORGANIZATION,
                        INVITEE_ACCOUNT, MemberRole.VIEWER, null, Instant.now(), Instant.now(),
                        Instant.now(), Instant.now(), 1L)));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> inviteMember.invite(actor, caller(MemberRole.OWNER), EMAIL,
                        MemberRole.VIEWER))
                .withMessageContaining("already a member");
    }

    @Test
    void anOutstandingInvitationIsADifferentSentence() {
        given(members.findByOrganizationIdAndAccountId(ORGANIZATION, INVITEE_ACCOUNT))
                .willReturn(Optional.of(new Member(UUID.randomUUID(), ORGANIZATION,
                        INVITEE_ACCOUNT, MemberRole.VIEWER, null, Instant.now(), null,
                        Instant.now(), Instant.now(), 1L)));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> inviteMember.invite(actor, caller(MemberRole.OWNER), EMAIL,
                        MemberRole.VIEWER))
                .withMessageContaining("has not answered");
    }

    private static Membership caller(MemberRole role) {
        return new Membership(ORGANIZATION, CALLER_ACCOUNT, role);
    }
}
