package lhqm.furimeo.wisper.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.lang.reflect.RecordComponent;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretCipher;

/**
 * A destination's secret access key must reach exactly two places: the column it is
 * encrypted into, and the command that carries it to the node that needs it. Nowhere else -
 * not a log line, not an audit detail, not a page prop.
 *
 * <p>This is a test rather than a comment because the leak is silent. A record's generated
 * {@code toString} prints every component, so a single {@code log.debug("saving {}", x)}
 * added in a hurry two years from now puts a customer's object-store credential into a file
 * that gets copied into support tickets. The assertions below are what make that a red test
 * instead of an incident.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DestinationSecrecyTest {

    private static final String PLAINTEXT_SECRET = "wJalrXUtnFEMI-K7MDENG-bPxRfiCYEXAMPLEKEY";

    private final SecretCipher cipher = new RecordingCipher();

    @Mock
    private BackupDestinationRepository destinations;

    @Mock
    private AuditTrail audit;

    @Captor
    private ArgumentCaptor<AuditEntry> entries;

    private final ValidateDestinationDraft validation = new ValidateDestinationDraft();

    private String envelope;

    @BeforeEach
    void encryptTheSecret() {
        envelope = cipher.encrypt(PLAINTEXT_SECRET);
        given(destinations.save(any())).willAnswer(call -> call.getArgument(0));
        given(destinations.existsInScope(any(), any())).willReturn(false);
    }

    @Nested
    class WhatToStringPrints {

        @Test
        void theStoredRowPrintsNeitherTheEnvelopeNorTheSecret() {
            BackupDestination destination = BackupFixture.s3Destination(envelope);

            String printed = destination.toString();

            assertThat(printed).doesNotContain(envelope).doesNotContain(PLAINTEXT_SECRET);
            assertThat(printed).contains("<redacted>");
            // The parts that are not secret are still there, or the line is useless.
            assertThat(printed).contains("wisper-backups").contains("AKIAEXAMPLE");
        }

        @Test
        void theDecryptedCredentialsPrintEverythingExceptTheKey() {
            DestinationCredentials credentials = new DestinationCredentials(DestinationKind.S3,
                    "https://s3.example.com", "eu-central-1", "wisper-backups", "tenant-a/",
                    "AKIAEXAMPLE", PLAINTEXT_SECRET, true, "", "");

            String printed = credentials.toString();

            assertThat(printed).doesNotContain(PLAINTEXT_SECRET);
            assertThat(printed).contains("<redacted>").contains("wisper-backups");
        }

        @Test
        void theFormDraftPrintsWhatWasTypedExceptTheKey() {
            DestinationDraft draft = s3Draft(PLAINTEXT_SECRET);

            assertThat(draft.toString()).doesNotContain(PLAINTEXT_SECRET);
            assertThat(draft.toString()).contains("<redacted>").contains("offsite");
        }

        @Test
        void anEnvelopeIsStillNotSafeToPrintEvenThoughItIsEncrypted() {
            // It is one offline attack from being a credential, and "encrypted" is not an
            // argument anybody makes after a log file leaks.
            BackupDestination destination = BackupFixture.s3Destination(envelope);

            assertThat(destination.toString()).doesNotContain(envelope.substring(3));
        }
    }

    @Nested
    class WhatTheAuditTrailKeeps {

        @Test
        void creatingADestinationRecordsWhereItPointsAndNotWhatOpensIt() {
            new CreateBackupDestination(destinations, validation, cipher, audit)
                    .create(actor(), BackupFixture.ORGANIZATION, s3Draft(PLAINTEXT_SECRET));

            verify(audit).record(entries.capture());
            AuditEntry entry = entries.getValue();
            assertThat(entry.action()).isEqualTo("backup_destination.create");
            assertThat(entry.detail()).doesNotContain(PLAINTEXT_SECRET);
            assertThat(entry.detail()).contains("wisper-backups").contains("AKIAEXAMPLE");
        }

        @Test
        void rotatingAKeySaysThatItWasRotatedAndNotWhatTo() {
            BackupDestination stored = BackupFixture.s3Destination(envelope);
            given(destinations.findById(stored.id())).willReturn(Optional.of(stored));

            new UpdateBackupDestination(destinations, validation, cipher, audit)
                    .update(actor(), BackupFixture.ORGANIZATION, stored.id(),
                            s3Draft("a-completely-new-secret-key-value"), true);

            verify(audit).record(entries.capture());
            assertThat(entries.getValue().detail())
                    .doesNotContain("a-completely-new-secret-key-value")
                    .contains("secret key replaced");
        }

        @Test
        void aFailedCheckRecordsTheStoresAnswerAndNotTheCredential(@Mock CheckS3Bucket s3) {
            BackupDestination stored = BackupFixture.s3Destination(envelope);
            given(destinations.findById(stored.id())).willReturn(Optional.of(stored));
            given(s3.probe(any())).willReturn(Optional.of("Access denied (AccessDenied)."));

            String outcome = new VerifyDestination(destinations,
                    new ReadDestinationCredentials(cipher, BackupFixture.settings()), validation,
                    s3, audit).verify(actor(), BackupFixture.ORGANIZATION, stored.id());

            assertThat(outcome).isEqualTo("Access denied (AccessDenied).");
            verify(audit).record(entries.capture());
            assertThat(entries.getValue().detail())
                    .doesNotContain(PLAINTEXT_SECRET)
                    .doesNotContain(envelope);
        }
    }

    @Nested
    class WhatReachesAPage {

        @Test
        void theViewRecordHasNoFieldThatCouldHoldASecret() {
            // An Inertia prop is JSON inside the HTML of the response. A component named
            // secretAccessKey on this record would be a credential in the page source, and
            // nothing would fail - it would simply start being there.
            for (RecordComponent component : DestinationView.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .doesNotContain("secret")
                        .doesNotContain("passphrase")
                        .doesNotContain("password");
            }
        }
    }

    @Nested
    class WhatReachesANode {

        @Test
        void theCommandDoesCarryTheKeyBecauseTheNodeIsTheSideWithTheBytes() {
            DestinationCredentials credentials = new ReadDestinationCredentials(cipher,
                    BackupFixture.settings()).of(BackupFixture.s3Destination(envelope));

            assertThat(credentials.secretAccessKey()).isEqualTo(PLAINTEXT_SECRET);
            assertThat(ComposeRunBackup.destinationOf(credentials).getS3().getSecretAccessKey())
                    .isEqualTo(PLAINTEXT_SECRET);
        }

        @Test
        void aLocalDestinationCarriesNoCredentialAtAll() {
            BackupDestination local = BackupDestination.local(UUID.randomUUID(),
                    BackupFixture.ORGANIZATION, "on-node",
                    DestinationCredentials.NODE_BACKUP_ROOT + "/nightly", null);

            DestinationCredentials credentials = new ReadDestinationCredentials(cipher,
                    BackupFixture.settings()).of(local);

            assertThat(credentials.secretAccessKey()).isEmpty();
            assertThat(credentials.localPrefix()).isEqualTo("nightly");
        }
    }

    private static DestinationDraft s3Draft(String secret) {
        return new DestinationDraft("offsite", DestinationKind.S3, "https://s3.example.com",
                "eu-central-1", "wisper-backups", "tenant-a/", "AKIAEXAMPLE", secret, "", "");
    }

    private static AuditActor actor() {
        return AuditActor.system("test");
    }
}
