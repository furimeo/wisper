package lhqm.furimeo.wisper.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * The rules a destination has to satisfy, and the field each refusal names.
 *
 * <p>The local-path rules are the security-relevant half. What the panel stores is an
 * absolute directory and what travels to a node is the part after the node's backup root; a
 * path with {@code ..} in it, or one naming {@code /etc}, is a request for a node to write a
 * customer's archive somewhere it must never write. The node checks again on arrival - a
 * path that crosses a network and reaches a filesystem call is never trusted once - and this
 * is the first of the two checks.
 */
class ValidateDestinationDraftTest {

    private final ValidateDestinationDraft validation = new ValidateDestinationDraft();

    @Nested
    class LocalPaths {

        @Test
        void aDirectoryUnderTheNodeBackupRootIsAccepted() {
            assertThat(validation.requireLocalPath(
                    DestinationCredentials.NODE_BACKUP_ROOT + "/nightly"))
                    .isEqualTo(DestinationCredentials.NODE_BACKUP_ROOT + "/nightly");
        }

        @Test
        void aTrailingSlashIsRemovedSoTwoSpellingsAreOnePath() {
            assertThat(validation.requireLocalPath(
                    DestinationCredentials.NODE_BACKUP_ROOT + "/nightly/"))
                    .isEqualTo(DestinationCredentials.NODE_BACKUP_ROOT + "/nightly");
        }

        @Test
        void traversalIsRefusedOutright() {
            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> validation.requireLocalPath(
                            DestinationCredentials.NODE_BACKUP_ROOT + "/../../etc"))
                    .withMessageContaining("..");
        }

        @Test
        void aRelativePathIsRefused() {
            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> validation.requireLocalPath("backups/nightly"))
                    .withMessageContaining("absolute");
        }

        @Test
        void aSystemDirectoryIsRefusedByName() {
            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> validation.requireLocalPath("/etc"))
                    .withMessageContaining("system directory");
        }

        @Test
        void theBackupRootItselfIsRefusedSoTwoDestinationsCannotShareIt() {
            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> validation.requireLocalPath(
                            DestinationCredentials.NODE_BACKUP_ROOT));
        }

        @Test
        void aPathOutsideTheRootStillLandsInsideIt() {
            // Accepted, and the wire prefix says where it will actually go. The form tells
            // the operator this rather than letting a panel-side string decide where a node
            // writes.
            assertThat(validation.requireLocalPath("/srv/backups")).isEqualTo("/srv/backups");
            assertThat(DestinationCredentials.localPrefixOf("/srv/backups"))
                    .isEqualTo("srv/backups");
        }

        @Test
        void aPathInsideTheRootTravelsAsTheTailAlone() {
            assertThat(DestinationCredentials.localPrefixOf(
                    DestinationCredentials.NODE_BACKUP_ROOT + "/tenant-a/nightly"))
                    .isEqualTo("tenant-a/nightly");
        }
    }

    @Nested
    class S3Settings {

        @Test
        void acompleteDraftIsAccepted() {
            assertThatNoException().isThrownBy(() -> validation.check(s3("offsite",
                    "https://s3.example.com", "wisper-backups", "AKIA", "secret"), true));
        }

        @Test
        void anEndpointWithAPathIsRefusedBecauseItWouldNotBeSigned() {
            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> validation.check(s3("offsite",
                            "https://s3.example.com/wisper", "wisper-backups", "AKIA", "s"), true))
                    .withMessageContaining("Leave the path off");
        }

        @Test
        void aSchemeNobodySpeaksIsRefused() {
            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> validation.check(s3("offsite", "s3://bucket",
                            "wisper-backups", "AKIA", "s"), true));
        }

        @Test
        void aBucketNameS3WouldNotAcceptIsRefusedHereRatherThanOnTheFirstBackup() {
            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> validation.check(s3("offsite", "https://s3.example.com",
                            "Wisper_Backups", "AKIA", "s"), true));
        }

        @Test
        void aPrefixThatClimbsOutOfTheBucketIsRefused() {
            DestinationDraft draft = new DestinationDraft("offsite", DestinationKind.S3,
                    "https://s3.example.com", "eu-central-1", "wisper-backups", "../other",
                    "AKIA", "secret", "", "");

            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> validation.check(draft, true));
        }

        @Test
        void aMissingSecretIsRefusedOnCreateAndAllowedOnEdit() {
            DestinationDraft noSecret = s3("offsite", "https://s3.example.com",
                    "wisper-backups", "AKIA", "");

            assertThatExceptionOfType(RequestRejected.class)
                    .isThrownBy(() -> validation.check(noSecret, true));
            assertThatNoException().isThrownBy(() -> validation.check(noSecret, false));
        }
    }

    private static DestinationDraft s3(String name, String endpoint, String bucket,
                                       String accessKeyId, String secret) {
        return new DestinationDraft(name, DestinationKind.S3, endpoint, "eu-central-1", bucket,
                "tenant-a/", accessKeyId, secret, "", "");
    }
}
