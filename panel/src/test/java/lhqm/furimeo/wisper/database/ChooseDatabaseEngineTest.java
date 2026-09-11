package lhqm.furimeo.wisper.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.node.Node;
import lhqm.furimeo.wisper.node.NodeRepository;
import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Where a new customer database is put.
 *
 * <p>Three rules, and each of them costs money or privacy if it is wrong: a dedicated engine
 * is reused rather than multiplied, a shared engine on a machine that cannot receive a spec
 * is skipped rather than chosen, and a platform with nothing running yet creates an engine
 * instead of telling its first customer to come back later.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChooseDatabaseEngineTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID PLACEABLE = UUID.randomUUID();
    private static final UUID DRAINING = UUID.randomUUID();

    @Mock
    private DatabaseEngineRepository engines;

    @Mock
    private CreateDatabaseEngine creation;

    @Mock
    private NodeRepository nodes;

    private final AuditActor actor = AuditActor.system("test");

    private ChooseDatabaseEngine choose;

    @BeforeEach
    void oneNodeThatCanTakeWork() {
        choose = new ChooseDatabaseEngine(engines, creation, nodes);

        given(nodes.findPlaceable()).willReturn(List.of(node(PLACEABLE)));
        given(nodes.findById(PLACEABLE)).willReturn(Optional.of(node(PLACEABLE)));
        given(nodes.findById(DRAINING)).willReturn(Optional.of(drainingNode()));
        given(engines.findByNodeIdOrderByPort(any())).willReturn(List.of());
        given(engines.findSharedCandidates(any())).willReturn(List.of());
        given(engines.findDedicatedFor(any(), any())).willReturn(Optional.empty());
        given(creation.shared(any(), any(), any()))
                .willAnswer(call -> sharedOn(call.getArgument(1), call.getArgument(2)));
        given(creation.dedicated(any(), any(), any(), any()))
                .willAnswer(call -> dedicatedOn(call.getArgument(1)));
    }

    @Test
    void anExistingSharedEngineOnAPlaceableNodeIsUsedRatherThanAnother() {
        DatabaseEngine existing = sharedOn(PLACEABLE);
        given(engines.findSharedCandidates("POSTGRES")).willReturn(List.of(existing));

        DatabaseEngine chosen = choose.forNewDatabase(actor, ORGANIZATION, EngineKind.POSTGRES,
                false);

        assertThat(chosen.id()).isEqualTo(existing.id());
        verify(creation, never()).shared(any(), any(), any());
    }

    @Test
    void aSharedEngineOnANodeThatCannotReceiveASpecIsSkipped() {
        // The row would be written and the database never created: a draining machine takes
        // no new placements, so nothing would ever reconcile it into existence.
        given(engines.findSharedCandidates("POSTGRES")).willReturn(List.of(sharedOn(DRAINING)));

        DatabaseEngine chosen = choose.forNewDatabase(actor, ORGANIZATION, EngineKind.POSTGRES,
                false);

        verify(creation).shared(any(), eq(PLACEABLE), eq(EngineKind.POSTGRES));
        assertThat(chosen.nodeId()).isEqualTo(PLACEABLE);
    }

    @Test
    void aPlatformWithNoEngineYetCreatesOneForItsFirstCustomer() {
        DatabaseEngine chosen = choose.forNewDatabase(actor, ORGANIZATION, EngineKind.MYSQL,
                false);

        verify(creation).shared(any(), eq(PLACEABLE), eq(EngineKind.MYSQL));
        assertThat(chosen.engine()).isEqualTo(EngineKind.MYSQL);
        assertThat(chosen.mode()).isEqualTo(EngineMode.SHARED);
    }

    @Test
    void anOrganizationsOwnEngineIsReusedInsteadOfGettingASecondContainer() {
        // The point of paying for isolation is not being queried by strangers. A second
        // private container gives no more of that and costs another 30-50 MB idle.
        DatabaseEngine mine = dedicatedOn(PLACEABLE);
        given(engines.findDedicatedFor(ORGANIZATION, "POSTGRES")).willReturn(Optional.of(mine));

        DatabaseEngine chosen = choose.forNewDatabase(actor, ORGANIZATION, EngineKind.POSTGRES,
                true);

        assertThat(chosen.id()).isEqualTo(mine.id());
        verify(creation, never()).dedicated(any(), any(), any(), any());
    }

    @Test
    void askingForDedicatedWhenThereIsNoneCreatesOneOwnedByTheOrganization() {
        DatabaseEngine chosen = choose.forNewDatabase(actor, ORGANIZATION, EngineKind.POSTGRES,
                true);

        verify(creation).dedicated(any(), eq(PLACEABLE), eq(ORGANIZATION),
                eq(EngineKind.POSTGRES));
        assertThat(chosen.mode()).isEqualTo(EngineMode.DEDICATED);
        assertThat(chosen.organizationId()).isEqualTo(ORGANIZATION);
    }

    @Test
    void aSharedEngineIsNeverChosenForACustomerWhoAskedForTheirOwn() {
        given(engines.findSharedCandidates("POSTGRES"))
                .willReturn(List.of(sharedOn(PLACEABLE)));

        DatabaseEngine chosen = choose.forNewDatabase(actor, ORGANIZATION, EngineKind.POSTGRES,
                true);

        assertThat(chosen.isShared()).isFalse();
    }

    @Test
    void withNoPlaceableNodeTheRequestIsRefusedRatherThanQueued() {
        // A capacity problem an operator has to solve. Writing the row anyway would leave a
        // customer watching a screen for a database nothing is going to create.
        given(nodes.findPlaceable()).willReturn(List.of());

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> choose.forNewDatabase(actor, ORGANIZATION, EngineKind.POSTGRES,
                        false))
                .withMessageContaining("No node is available");
    }

    private static Node node(UUID id) {
        return Node.created(id, "node-" + id.toString().substring(0, 4), "", "203.0.113.10",
                        List.of())
                .enrolled("fingerprint-" + id, "key", "hash-" + id, "https://panel.example:9090",
                        Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static Node drainingNode() {
        return node(DRAINING).draining(Instant.parse("2026-02-01T00:00:00Z"));
    }

    private static DatabaseEngine sharedOn(UUID nodeId) {
        return sharedOn(nodeId, EngineKind.POSTGRES);
    }

    private static DatabaseEngine sharedOn(UUID nodeId, EngineKind kind) {
        return DatabaseEngine.shared(UUID.randomUUID(), nodeId, kind, "17",
                "postgres:17-alpine", kind.defaultPort(), "v1.nonce.admin");
    }

    private static DatabaseEngine dedicatedOn(UUID nodeId) {
        return DatabaseEngine.dedicated(UUID.randomUUID(), nodeId, ORGANIZATION,
                EngineKind.POSTGRES, "17", "postgres:17-alpine", 15432, "v1.nonce.admin");
    }
}
