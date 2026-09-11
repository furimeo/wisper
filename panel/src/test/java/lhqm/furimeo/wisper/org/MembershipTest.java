package lhqm.furimeo.wisper.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The two authorization boundaries, spelled out per role.
 *
 * <p>Cheap to write and worth having, because these are the predicates every other
 * package calls and an off-by-one in the enum order would move a boundary silently: the
 * roles are declared in descending authority so {@code outranks} can be an ordinal
 * comparison, and that is exactly the kind of ordering somebody tidies alphabetically one
 * day.
 */
class MembershipTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"OWNER", "ADMIN", "DEVELOPER"})
    void everybodyButAViewerMayWrite(MemberRole role) {
        assertThat(membership(role).canWrite()).isTrue();
        assertThatNoException().isThrownBy(() -> membership(role).requireWrite("service.start"));
    }

    @Test
    void aViewerMayNotWrite() {
        Membership viewer = membership(MemberRole.VIEWER);

        assertThat(viewer.canWrite()).isFalse();
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> viewer.requireWrite("service.start"))
                .withMessageContaining("service.start")
                .withMessageContaining("viewer");
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"OWNER", "ADMIN"})
    void ownersAndAdministratorsMayAdminister(MemberRole role) {
        assertThat(membership(role).canAdminister()).isTrue();
        assertThatNoException().isThrownBy(
                () -> membership(role).requireAdministration("member.invite"));
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"DEVELOPER", "VIEWER"})
    void developersAndViewersMayNotAdminister(MemberRole role) {
        assertThat(membership(role).canAdminister()).isFalse();
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> membership(role).requireAdministration("member.invite"));
    }

    @Test
    void onlyAnOwnerOwns() {
        assertThat(membership(MemberRole.OWNER).isOwner()).isTrue();
        assertThat(membership(MemberRole.ADMIN).isOwner()).isFalse();
        assertThatExceptionOfType(PermissionDenied.class).isThrownBy(
                () -> membership(MemberRole.ADMIN).requireOwnership("organization.delete"));
    }

    @Test
    void authorityDescendsInDeclarationOrder() {
        assertThat(MemberRole.OWNER.outranks(MemberRole.ADMIN)).isTrue();
        assertThat(MemberRole.ADMIN.outranks(MemberRole.DEVELOPER)).isTrue();
        assertThat(MemberRole.DEVELOPER.outranks(MemberRole.VIEWER)).isTrue();
        assertThat(MemberRole.VIEWER.outranks(MemberRole.OWNER)).isFalse();
        assertThat(MemberRole.ADMIN.atLeast(MemberRole.ADMIN)).isTrue();
        assertThat(MemberRole.DEVELOPER.atLeast(MemberRole.ADMIN)).isFalse();
    }

    @Test
    void aRefusalNamesTheRoleAndTheActionSoTheAuditDetailIsUseful() {
        PermissionDenied denied = new PermissionDenied("member.remove", MemberRole.DEVELOPER,
                "an owner or an administrator");

        assertThat(denied.action()).isEqualTo("member.remove");
        assertThat(denied.role()).isEqualTo(MemberRole.DEVELOPER);
        assertThat(denied.getMessage()).contains("an owner or an administrator");
    }

    @Test
    void aMembershipNeedsAllThreeParts() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new Membership(ORGANIZATION, ACCOUNT, null));
    }

    private static Membership membership(MemberRole role) {
        return new Membership(ORGANIZATION, ACCOUNT, role);
    }
}
