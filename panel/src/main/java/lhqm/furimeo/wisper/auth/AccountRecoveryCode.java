package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

/**
 * One single-use way past the TOTP challenge. Maps to {@code account_recovery_code}.
 *
 * <p>Hashed rather than encrypted, because the panel only ever answers "is this the code
 * you were given". A store that cannot produce the code back is one an attacker with
 * database access cannot read it out of either. The plaintext exists exactly once, on the
 * screen that generated the batch.
 *
 * <p>A spent code keeps its row with {@code usedAt} set rather than being deleted, which
 * is what makes "you have used three of your eight codes" answerable, and what stops a
 * replayed code looking like an unknown one.
 */
public record AccountRecoveryCode(
        @Id UUID id,
        UUID accountId,
        String codeHash,
        Instant usedAt,
        @CreatedDate Instant createdAt,
        @Version Long version) {

    /** A fresh, unused code for an account. */
    public static AccountRecoveryCode issued(UUID accountId, String codeHash) {
        return new AccountRecoveryCode(UUID.randomUUID(), accountId, codeHash, null, null, null);
    }

    /** Spent. There is no way back; a new batch replaces the whole set. */
    public AccountRecoveryCode consumed(Instant when) {
        return new AccountRecoveryCode(id, accountId, codeHash, when, createdAt, version);
    }
}
