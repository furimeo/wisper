package lhqm.furimeo.wisper.crypto;

/**
 * Encrypts and decrypts the values the panel has to be able to read back.
 *
 * <p>Implemented by {@code lhqm.furimeo.wisper.crypto.AesGcmSecretCipher}, which holds
 * the keys from {@code wisper.crypto.keys} and does AES-256-GCM.
 *
 * <p>Four packages need this and none of them should own it: {@code auth} for
 * {@code account.totp_secret}, {@code service} for the webhook secret, the repository
 * credential and {@code secret.value}, {@code database} for the engine and customer
 * passwords, {@code backup} for the S3 credentials and the archive passphrase. Putting
 * it in any one of them would make the other three depend on a domain they have nothing
 * to do with.
 *
 * <h2>What must not be here</h2>
 *
 * <p>Anything the panel only ever compares is hashed, not encrypted, and does not come
 * near this interface: {@code account.password_hash} (BCrypt),
 * {@code account_recovery_code.code_hash}, {@code session.session_id_hash},
 * {@code node.credential_hash}, {@code node_enrollment_token.token_hash},
 * {@code api_token.token_hash}. Encrypting one of those would mean the panel could read
 * back a credential it has no reason to be able to read back.
 *
 * <p>TLS private keys are never stored at all. The node owns its certificates and the
 * key never leaves it; {@code certificate} holds metadata only (design §11.3).
 *
 * <h2>Rotation</h2>
 *
 * <p>{@link #encrypt} always uses the current key and stamps its version into the
 * envelope. {@link #decrypt} reads whichever version the envelope names, so old values
 * keep working and a rotation is a background pass that decrypts with the old key and
 * re-encrypts with the new one, one row at a time, with no downtime and no flag day.
 */
public interface SecretCipher {

    /**
     * Encrypts with the current key and returns the value to store in the column.
     *
     * <p>A fresh random nonce every time, so encrypting the same plaintext twice gives
     * two different envelopes - which is what stops "these two customers have the same
     * database password" being visible to anyone with read access to the table.
     *
     * @param plaintext the value; may be empty, must not be null
     * @return an envelope in {@link SecretEnvelope} form, ready for the column
     */
    String encrypt(String plaintext);

    /**
     * Decrypts a stored envelope.
     *
     * @throws IllegalArgumentException if the stored value is not an envelope, which
     *         means a plaintext value reached an encrypted column
     * @throws IllegalStateException if the key version the envelope names is not
     *         configured, or the authentication tag does not verify. Both are refusals
     *         to guess: a value that will not authenticate has been altered, and
     *         returning something plausible for it is worse than failing.
     */
    String decrypt(String envelope);

    /**
     * The key version {@link #encrypt} is currently stamping.
     *
     * <p>What the rotation pass compares against to find the rows it still has to
     * rewrite, and what an operator is shown to confirm a rotation finished.
     */
    int currentKeyVersion();
}
