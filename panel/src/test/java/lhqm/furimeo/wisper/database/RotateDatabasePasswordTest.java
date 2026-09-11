package lhqm.furimeo.wisper.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.util.unit.DataSize;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;

/**
 * Rotation, and the one promise it makes: when it returns, the previous password does not
 * work any more.
 *
 * <p>Which means the interesting cases are the failures. A rotation that could not reach the
 * engine must leave the old credential alone and say so, because a panel that reports a
 * leaked password as revoked when it is not is worse than one that reports nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RotateDatabasePasswordTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID DATABASE = UUID.randomUUID();
    private static final UUID ENGINE = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private static final String OLD_PASSWORD = "TheOldOneThatMustStopWorking01";
    private static final String OLD_ENVELOPE = "v1.nonce." + OLD_PASSWORD;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private ManagedDatabaseRepository databases;

    @Mock
    private DatabaseEngineRepository engines;

    @Mock
    private NodeConnections nodes;

    @Mock
    private SecretCipher cipher;

    @Mock
    private AuditTrail audit;

    private final DatabaseSettings settings = new DatabaseSettings(
            "postgres:17-alpine", "17", "mysql:8.4", "8.4",
            DataSize.ofGigabytes(1), DataSize.ofGigabytes(100), 15432, 15999,
            Duration.ofMinutes(5), Duration.ofSeconds(45));

    private final AuditActor actor = AuditActor.system("test");
    private final Membership membership = new Membership(ORGANIZATION, ACCOUNT, MemberRole.OWNER);

    private RotateDatabasePassword rotate;

    @BeforeEach
    void aReadyDatabaseOnAReachableNode() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        rotate = new RotateDatabasePassword(transactionManager, databases, engines, nodes, cipher,
                settings, audit);

        given(databases.findOwnedBy(DATABASE, ORGANIZATION)).willReturn(Optional.of(ready()));
        given(databases.findById(DATABASE)).willReturn(Optional.of(ready()));
        given(databases.save(any())).willAnswer(call -> call.getArgument(0));
        given(engines.findById(ENGINE)).willReturn(Optional.of(engine()));
        given(cipher.encrypt(anyString())).willAnswer(call -> "v1.nonce." + call.getArgument(0));
        given(nodes.call(any(), any()))
                .willReturn(CompletableFuture.completedFuture(
                        CommandResult.newBuilder().setOk(true).build()));
    }

    @Test
    void theEngineIsToldTheNewPasswordAndTheRowRecordsTheSameOne() {
        ConnectionString details = rotate.rotate(actor, membership, DATABASE);

        ArgumentCaptor<PanelMessage.Builder> sent =
                ArgumentCaptor.forClass(PanelMessage.Builder.class);
        verify(nodes).call(eq(NODE), sent.capture());
        String pushed = sent.getValue().getRotateDatabasePassword().getNewPassword();

        assertThat(pushed).isNotEqualTo(OLD_PASSWORD);
        assertThat(DatabasePassword.isSafe(pushed)).isTrue();
        assertThat(details.password()).isEqualTo(pushed);

        ArgumentCaptor<ManagedDatabase> saved = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(databases).save(saved.capture());
        assertThat(saved.getValue().dbPassword()).isEqualTo("v1.nonce." + pushed);
    }

    @Test
    void theOldEnvelopeIsGoneFromTheRowOnceTheEngineHasAccepted() {
        rotate.rotate(actor, membership, DATABASE);

        ArgumentCaptor<ManagedDatabase> saved = ArgumentCaptor.forClass(ManagedDatabase.class);
        verify(databases).save(saved.capture());
        assertThat(saved.getValue().dbPassword()).isNotEqualTo(OLD_ENVELOPE);
        assertThat(saved.getValue().passwordRotatedAt()).isNotNull();
    }

    @Test
    void theCommandNamesTheLoginAndTheEngineSoTheNodeDoesNotHaveToGuess() {
        rotate.rotate(actor, membership, DATABASE);

        ArgumentCaptor<PanelMessage.Builder> sent =
                ArgumentCaptor.forClass(PanelMessage.Builder.class);
        verify(nodes).call(any(), sent.capture());
        var command = sent.getValue().getRotateDatabasePassword();

        assertThat(command.getId()).isEqualTo(DATABASE.toString());
        assertThat(command.getUsername()).isEqualTo("acme_app_abcdef");
        assertThat(command.getEngine())
                .isEqualTo(lhqm.furimeo.wisper.proto.v1.DatabaseEngine.DATABASE_ENGINE_POSTGRES);
    }

    @Test
    void anEngineThatRefusesLeavesTheOldPasswordWorkingAndSaysSo() {
        given(nodes.call(any(), any())).willReturn(CompletableFuture.completedFuture(
                CommandResult.newBuilder().setOk(false).setDetail("role does not exist").build()));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> rotate.rotate(actor, membership, DATABASE))
                .withMessageContaining("role does not exist");

        // Nothing was written, so the credential the customer already has is still the one
        // in the row and still the one the engine accepts.
        verify(databases, never()).save(any());
        verify(audit).record(any());
    }

    @Test
    void aNodeThatIsNotAnsweringLeavesTheOldPasswordWorking() {
        given(nodes.call(any(), any()))
                .willReturn(CompletableFuture.failedFuture(new NodeOffline(NODE)));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> rotate.rotate(actor, membership, DATABASE))
                .withMessageContaining("still works");

        verify(databases, never()).save(any());
    }

    @Test
    void aDatabaseTheNodeHasNotCreatedYetHasNoLoginToRotate() {
        given(databases.findOwnedBy(DATABASE, ORGANIZATION)).willReturn(Optional.of(pending()));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> rotate.rotate(actor, membership, DATABASE));

        verify(nodes, never()).call(any(), any());
    }

    @Test
    void aViewerCannotRotate() {
        Membership viewer = new Membership(ORGANIZATION, ACCOUNT, MemberRole.VIEWER);

        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> rotate.rotate(actor, viewer, DATABASE));

        verify(nodes, never()).call(any(), any());
    }

    @Test
    void anOverQuotaDatabaseCanStillBeRotatedBecauseSuspensionIsNotRevocation() {
        given(databases.findOwnedBy(DATABASE, ORGANIZATION))
                .willReturn(Optional.of(ready().suspended("over quota")));

        ConnectionString details = rotate.rotate(actor, membership, DATABASE);

        assertThat(details.password()).isNotEqualTo(OLD_PASSWORD);
    }

    private static ManagedDatabase pending() {
        return ManagedDatabase.requested(DATABASE, PROJECT, ENGINE, "acme_app", "acme_app_abcdef",
                OLD_ENVELOPE, "UTF8", null, DataSize.ofGigabytes(1).toBytes());
    }

    private static ManagedDatabase ready() {
        return pending().ready(Instant.parse("2026-02-01T09:00:00Z"));
    }

    private static DatabaseEngine engine() {
        return DatabaseEngine.shared(ENGINE, NODE, EngineKind.POSTGRES, "17",
                "postgres:17-alpine", 5432, "v1.nonce.admin");
    }
}
