package lhqm.furimeo.wisper.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.util.unit.DataSize;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;

/**
 * Dropping a customer database: the confirmation, the order the three steps happen in, and
 * what survives when the middle one fails.
 *
 * <p>This is the button that deletes a customer's tables, so the cases worth writing down
 * are the ones where it must <em>not</em> finish: a mistyped confirmation, a node that never
 * answered, an engine that refused. In all three the row has to still be there, visibly, with
 * a sentence on it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DropDatabaseTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID DATABASE = UUID.randomUUID();
    private static final UUID ENGINE = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private static final String NAME = "acme_app";

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private ManagedDatabaseRepository databases;

    @Mock
    private DatabaseEngineRepository engines;

    @Mock
    private NodeConnections nodes;

    @Mock
    private PublishNodeSpec specs;

    @Mock
    private AuditTrail audit;

    private final DatabaseSettings settings = new DatabaseSettings(
            "postgres:17-alpine", "17", "mysql:8.4", "8.4",
            DataSize.ofGigabytes(1), DataSize.ofGigabytes(100), 15432, 15999,
            Duration.ofMinutes(5), Duration.ofSeconds(45));

    private final AuditActor actor = AuditActor.system("test");
    private final Membership membership = new Membership(ORGANIZATION, ACCOUNT, MemberRole.OWNER);

    private DropDatabase dropDatabase;

    @BeforeEach
    void aReadyDatabaseOnAReachableNode() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        dropDatabase = new DropDatabase(transactionManager, databases, engines, nodes, specs,
                settings, audit);

        given(databases.findOwnedBy(DATABASE, ORGANIZATION)).willReturn(Optional.of(ready()));
        given(databases.findById(DATABASE)).willReturn(Optional.of(ready()));
        given(databases.save(any())).willAnswer(call -> call.getArgument(0));
        given(engines.findById(ENGINE)).willReturn(Optional.of(engine()));
        given(nodes.call(any(), any())).willReturn(CompletableFuture.completedFuture(
                CommandResult.newBuilder().setOk(true).build()));
    }

    @Test
    void aConfirmationThatDoesNotMatchChangesNothingAtAll() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> dropDatabase.drop(actor, membership, DATABASE, "acme_ap"))
                .satisfies(refused -> assertThat(refused.field()).isEqualTo("confirmation"));

        verify(databases, never()).save(any());
        verify(databases, never()).deleteById(any());
        verify(nodes, never()).call(any(), any());
        verify(specs, never()).toNode(any(), anyString());
    }

    @Test
    void theGrantLeavesTheSpecBeforeTheCommandGoesOut() {
        // Committing DELETING first is what makes an unanswered drop converge anyway:
        // omission is deletion, so the node removes it on its next pass regardless.
        dropDatabase.drop(actor, membership, DATABASE, NAME);

        InOrder order = inOrder(databases, specs, nodes);
        ArgumentCaptor<ManagedDatabase> marked = ArgumentCaptor.forClass(ManagedDatabase.class);
        order.verify(databases).save(marked.capture());
        order.verify(specs).toNode(eq(NODE), anyString());
        order.verify(nodes).call(eq(NODE), any());
        order.verify(databases).deleteById(DATABASE);

        assertThat(marked.getValue().state()).isEqualTo(ManagedDatabaseState.DELETING);
    }

    @Test
    void theCommandAsksForTheDataToGoAndNamesBothTheDatabaseAndItsLogin() {
        dropDatabase.drop(actor, membership, DATABASE, NAME);

        ArgumentCaptor<PanelMessage.Builder> sent =
                ArgumentCaptor.forClass(PanelMessage.Builder.class);
        verify(nodes).call(any(), sent.capture());
        var command = sent.getValue().getDropDatabase();

        assertThat(command.getId()).isEqualTo(DATABASE.toString());
        assertThat(command.getDatabaseName()).isEqualTo(NAME);
        assertThat(command.getUsername()).isEqualTo("acme_app_abcdef");
        assertThat(command.getDropData()).isTrue();
        assertThat(command.getEngine())
                .isEqualTo(lhqm.furimeo.wisper.proto.v1.DatabaseEngine.DATABASE_ENGINE_POSTGRES);
    }

    @Test
    void aNodeThatNeverAnsweredLeavesTheRowVisibleInsteadOfDeletingIt() {
        given(nodes.call(any(), any()))
                .willReturn(CompletableFuture.failedFuture(new NodeOffline(NODE)));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> dropDatabase.drop(actor, membership, DATABASE, NAME))
                .withMessageContaining("nothing has been lost");

        verify(databases, never()).deleteById(any());
        ArgumentCaptor<ManagedDatabase> saved = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(databases, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().lastError()).contains("not answering");
    }

    @Test
    void anEngineThatRefusesLeavesTheRowAndSaysWhy() {
        given(nodes.call(any(), any())).willReturn(CompletableFuture.completedFuture(
                CommandResult.newBuilder().setOk(false)
                        .setDetail("database is being accessed by other users").build()));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> dropDatabase.drop(actor, membership, DATABASE, NAME))
                .withMessageContaining("other users");

        verify(databases, never()).deleteById(any());
    }

    @Test
    void aViewerCannotDropAnything() {
        Membership viewer = new Membership(ORGANIZATION, ACCOUNT, MemberRole.VIEWER);

        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> dropDatabase.drop(actor, viewer, DATABASE, NAME));

        verify(databases, never()).save(any());
        verify(nodes, never()).call(any(), any());
    }

    @Test
    void aDatabaseInAnotherOrganizationIsNotFoundRatherThanForbidden() {
        given(databases.findOwnedBy(DATABASE, ORGANIZATION)).willReturn(Optional.empty());

        assertThatExceptionOfType(lhqm.furimeo.wisper.web.NotFoundException.class)
                .isThrownBy(() -> dropDatabase.drop(actor, membership, DATABASE, NAME));

        verify(nodes, never()).call(any(), any());
    }

    @Test
    void aSuccessfulDropRepublishesTheSpecSoTheGrantIsGoneForGood() {
        dropDatabase.drop(actor, membership, DATABASE, NAME);

        verify(databases).deleteById(DATABASE);
        verify(specs, times(2)).toNode(eq(NODE), anyString());
        verify(audit).record(any());
    }

    private static ManagedDatabase ready() {
        return ManagedDatabase.requested(DATABASE, PROJECT, ENGINE, NAME, "acme_app_abcdef",
                        "v1.nonce.secret", "UTF8", null, DataSize.ofGigabytes(1).toBytes())
                .ready(Instant.parse("2026-02-01T09:00:00Z"));
    }

    private static DatabaseEngine engine() {
        return DatabaseEngine.shared(ENGINE, NODE, EngineKind.POSTGRES, "17",
                "postgres:17-alpine", 5432, "v1.nonce.admin");
    }
}
