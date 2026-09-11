package lhqm.furimeo.wisper.backup;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.crypto.SecretCipher;

/**
 * Decrypts a destination's secret, once, at the moment it is needed.
 *
 * <p>The only place in the panel that turns {@code backup_destination.secret_access_key}
 * back into a credential. Having exactly one is the point: a reviewer can check that this
 * file does not log, and then check that nothing else calls
 * {@link SecretCipher#decrypt(String)} on that column.
 *
 * <p>The result travels to a node inside {@code RunBackup} or {@code RestoreBackup} - the
 * node is the side with the bytes and the bandwidth, so it is the side that talks to the
 * object store - and it is sent again on every command rather than remembered by the node,
 * because credentials rotate and a restore is exactly when a stale one is least welcome.
 */
@Component
public class ReadDestinationCredentials {

    private final SecretCipher cipher;
    private final BackupSettings settings;

    public ReadDestinationCredentials(SecretCipher cipher, BackupSettings settings) {
        this.cipher = cipher;
        this.settings = settings;
    }

    /**
     * The destination, usable.
     *
     * @throws IllegalArgumentException if the stored value is not an envelope, which means
     *         a plaintext credential was written into an encrypted column. Refusing beats
     *         sending a node a secret key that is actually the string "changeme"
     * @throws IllegalStateException if the key that encrypted it is not configured
     */
    public DestinationCredentials of(BackupDestination destination) {
        if (destination.kind() == DestinationKind.LOCAL) {
            return new DestinationCredentials(DestinationKind.LOCAL,
                    "", "", "", "", "", "", false, "",
                    DestinationCredentials.localPrefixOf(destination.localPath()));
        }
        return new DestinationCredentials(DestinationKind.S3,
                destination.endpoint(),
                orEmpty(destination.region()),
                destination.bucket(),
                destination.pathPrefix(),
                orEmpty(destination.accessKeyId()),
                cipher.decrypt(destination.secretAccessKey()),
                settings.s3PathStyle(),
                settings.serverSideEncryption(),
                "");
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
