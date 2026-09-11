package lhqm.furimeo.wisper.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The owner boundary and the last-owner guard.
 *
 * <p>Both are unrecoverable if they leak. An administrator who can mint owners is an
 * owner; an organization whose last owner has been demoted cannot be administered by
 * anybody ever again, and there is no screen in this panel that repairs it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChangeMemberRoleTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID CALLER_ACCOUNT = UUID.randomUUID();
    private static final UUID SUBJECT_ACCOUNT = UUID.randomUUID();
    private static final UUID MEMBER_ID = UUID.randomUUID();

    @Mock
    private MemberRepository members;

    @Mock
    private FindAccount accounts;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private ChangeMemberRole changeMemberRole;

    private final AuditActor actor = AuditActor.system("test");

    @Test
    void anOwnerPromotesADeveloperAndTheChangeIsRecorded() {
        existing(MemberRole.DEVELOPER, true);
        given(members.save(any())).willAnswer(call -> call.getArgument(0));
        given(accounts.byId(SUBJECT_ACCOUNT)).willReturn(Optional.of(
                new AccountRef(SUBJECT_ACCOUNT, "ada@example.com", "Ada", true)));

        Member changed = changeMemberRole.change(actor, caller(MemberRole.OWNER), MEMBER_ID,
                MemberRole.ADMIN);

        assertThat(changed.role()).isEqualTo(MemberRole.ADMIN);
        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().role()).isEqualTo(MemberRole.ADMIN);
        verify(audit).record(any());
    }

    @Test
    void aDeveloperMayNotChangeRolesAtAll() {
        assertThatExceptionOfType(PermissionDenied.class).isThrownBy(
                () -> changeMemberRole.change(actor, caller(MemberRole.DEVELOPER), MEMBER_ID,
                        MemberRole.ADMIN));
        verify(members, never()).save(any());
    }

    @Test
    void anAdministratorMayNotCreateAnOwner() {
        existing(MemberRole.DEVELOPER, true);

        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> changeMemberRole.change(actor, caller(MemberRole.ADMIN),
                        MEMBER_ID, MemberRole.OWNER))
                .withMessageContaining("an owner");
        verify(members, never()).save(any());
    }

    @Test
    void anAdministratorMayNotDemoteAnOwner() {
        existing(MemberRole.OWNER, true);

        assertThatExceptionOfType(PermissionDenied.class).isThrownBy(
                () -> changeMemberRole.change(actor, caller(MemberRole.ADMIN), MEMBER_ID,
                        MemberRole.VIEWER));
        verify(members, never()).save(any());
    }

    @Test
    void theLastOwnerCannotBeDemoted() {
        existing(MemberRole.OWNER, true);
        given(members.countOwners(ORGANIZATION)).willReturn(1L);

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> changeMemberRole.change(actor, caller(MemberRole.OWNER),
                        MEMBER_ID, MemberRole.ADMIN))
                .withMessageContaining("last owner");
        verify(members, never()).save(any());
    }

    @Test
    void anOwnerMayBeDemotedOnceThereIsASecondOne() {
        existing(MemberRole.OWNER, true);
        given(members.countOwners(ORGANIZATION)).willReturn(2L);
        given(members.save(any())).willAnswer(call -> call.getArgument(0));
        given(accounts.byId(SUBJECT_ACCOUNT)).willReturn(Optional.empty());

        Member changed = changeMemberRole.change(actor, caller(MemberRole.OWNER), MEMBER_ID,
                MemberRole.ADMIN);

        assertThat(changed.role()).isEqualTo(MemberRole.ADMIN);
    }

    @Test
    void aMemberOfAnotherOrganizationIsNotFound() {
        given(members.findById(MEMBER_ID)).willReturn(Optional.of(new Member(MEMBER_ID,
                UUID.randomUUID(), SUBJECT_ACCOUNT, MemberRole.VIEWER, null, Instant.now(),
                Instant.now(), Instant.now(), Instant.now(), 1L)));

        assertThatExceptionOfType(NotFoundException.class).isThrownBy(
                () -> changeMemberRole.change(actor, caller(MemberRole.OWNER), MEMBER_ID,
                        MemberRole.ADMIN));
    }

    @Test
    void settingTheRoleSomebodyAlreadyHasIsRefusedRatherThanSilentlyDoingNothing() {
        existing(MemberRole.VIEWER, true);

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> changeMemberRole.change(actor, caller(MemberRole.OWNER),
                        MEMBER_ID, MemberRole.VIEWER))
                .withMessageContaining("already");
    }

    @Test
    void anOutstandingInvitationCanBeReRoledWithoutTrippingTheOwnerCount() {
        existing(MemberRole.OWNER, false);
        given(members.countOwners(ORGANIZATION)).willReturn(1L);
        given(members.save(any())).willAnswer(call -> call.getArgument(0));
        given(accounts.byId(SUBJECT_ACCOUNT)).willReturn(Optional.empty());

        Member changed = changeMemberRole.change(actor, caller(MemberRole.OWNER), MEMBER_ID,
                MemberRole.ADMIN);

        assertThat(changed.role()).isEqualTo(MemberRole.ADMIN);
    }

    private void existing(MemberRole role, boolean accepted) {
        given(members.findById(MEMBER_ID)).willReturn(Optional.of(new Member(MEMBER_ID,
                ORGANIZATION, SUBJECT_ACCOUNT, role, null, Instant.now(),
                accepted ? Instant.now() : null, Instant.now(), Instant.now(), 1L)));
    }

    private static Membership caller(MemberRole role) {
        return new Membership(ORGANIZATION, CALLER_ACCOUNT, role);
    }
}
