package lhqm.furimeo.wisper.org;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Removal, and the one case that has to keep working without administration rights:
 * leaving.
 *
 * <p>A viewer who cannot remove anybody must still be able to remove themselves, or the
 * only way out of an organization is to ask the people you are trying to leave.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RemoveMemberTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID CALLER_ACCOUNT = UUID.randomUUID();
    private static final UUID OTHER_ACCOUNT = UUID.randomUUID();
    private static final UUID MEMBER_ID = UUID.randomUUID();

    @Mock
    private MemberRepository members;

    @Mock
    private FindAccount accounts;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private RemoveMember removeMember;

    private final AuditActor actor = AuditActor.system("test");

    @Test
    void anAdministratorRemovesADeveloper() {
        existing(OTHER_ACCOUNT, MemberRole.DEVELOPER, true);
        given(accounts.byId(OTHER_ACCOUNT)).willReturn(Optional.of(
                new AccountRef(OTHER_ACCOUNT, "ada@example.com", "Ada", true)));

        removeMember.remove(actor, caller(MemberRole.ADMIN), MEMBER_ID);

        verify(members).delete(any());
        verify(audit).record(any());
    }

    @Test
    void aViewerMayLeaveEvenThoughTheyMayRemoveNobodyElse() {
        existing(CALLER_ACCOUNT, MemberRole.VIEWER, true);
        given(accounts.byId(CALLER_ACCOUNT)).willReturn(Optional.empty());

        assertThatNoException().isThrownBy(
                () -> removeMember.remove(actor, caller(MemberRole.VIEWER), MEMBER_ID));
        verify(members).delete(any());
    }

    @Test
    void aViewerMayNotRemoveSomebodyElse() {
        existing(OTHER_ACCOUNT, MemberRole.VIEWER, true);

        assertThatExceptionOfType(PermissionDenied.class).isThrownBy(
                () -> removeMember.remove(actor, caller(MemberRole.VIEWER), MEMBER_ID));
        verify(members, never()).delete(any());
    }

    @Test
    void anAdministratorMayNotRemoveAnOwner() {
        existing(OTHER_ACCOUNT, MemberRole.OWNER, true);

        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> removeMember.remove(actor, caller(MemberRole.ADMIN), MEMBER_ID))
                .withMessageContaining("an owner");
        verify(members, never()).delete(any());
    }

    @Test
    void theLastOwnerCannotBeRemoved() {
        existing(OTHER_ACCOUNT, MemberRole.OWNER, true);
        given(members.countOwners(ORGANIZATION)).willReturn(1L);

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> removeMember.remove(actor, caller(MemberRole.OWNER), MEMBER_ID))
                .withMessageContaining("last owner");
        verify(members, never()).delete(any());
    }

    @Test
    void theLastOwnerCannotLeaveEither() {
        existing(CALLER_ACCOUNT, MemberRole.OWNER, true);
        given(members.countOwners(ORGANIZATION)).willReturn(1L);

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> removeMember.remove(actor, caller(MemberRole.OWNER), MEMBER_ID))
                .withMessageContaining("last owner");
        verify(members, never()).delete(any());
    }

    @Test
    void withdrawingAnUnacceptedOwnerInvitationDoesNotCountAgainstTheOwnerGuard() {
        existing(OTHER_ACCOUNT, MemberRole.OWNER, false);
        given(members.countOwners(ORGANIZATION)).willReturn(1L);
        given(accounts.byId(OTHER_ACCOUNT)).willReturn(Optional.empty());

        assertThatNoException().isThrownBy(
                () -> removeMember.remove(actor, caller(MemberRole.OWNER), MEMBER_ID));
        verify(members).delete(any());
    }

    @Test
    void aMemberOfAnotherOrganizationIsNotFound() {
        given(members.findById(MEMBER_ID)).willReturn(Optional.of(new Member(MEMBER_ID,
                UUID.randomUUID(), OTHER_ACCOUNT, MemberRole.VIEWER, null, Instant.now(),
                Instant.now(), Instant.now(), Instant.now(), 1L)));

        assertThatExceptionOfType(NotFoundException.class).isThrownBy(
                () -> removeMember.remove(actor, caller(MemberRole.OWNER), MEMBER_ID));
    }

    private void existing(UUID accountId, MemberRole role, boolean accepted) {
        given(members.findById(MEMBER_ID)).willReturn(Optional.of(new Member(MEMBER_ID,
                ORGANIZATION, accountId, role, null, Instant.now(),
                accepted ? Instant.now() : null, Instant.now(), Instant.now(), 1L)));
    }

    private static Membership caller(MemberRole role) {
        return new Membership(ORGANIZATION, CALLER_ACCOUNT, role);
    }
}
