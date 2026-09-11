package lhqm.furimeo.wisper.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes {@code account_recovery_code}.
 *
 * <p>{@link #findByCodeHash} looks the code up by its hash alone, without the account -
 * the hash column is globally unique, so a code cannot be presented against somebody
 * else's account, and the caller still checks the row belongs to whoever is answering the
 * challenge before spending it.
 */
public interface AccountRecoveryCodeRepository
        extends ListCrudRepository<AccountRecoveryCode, UUID> {

    /** The challenge lookup: SHA-256 of the normalised code. */
    Optional<AccountRecoveryCode> findByCodeHash(String codeHash);

    /** "You have four codes left", shown on the security page. */
    long countByAccountIdAndUsedAtIsNull(UUID accountId);

    /** The whole set, spent ones included, so the page can say four of ten. */
    List<AccountRecoveryCode> findByAccountId(UUID accountId);

    /**
     * Throws away the whole set.
     *
     * <p>Regenerating replaces rather than tops up: a batch is printed or saved as one
     * list, and leaving the old codes working would mean a leaked printout stays valid
     * after the person believed they had replaced it.
     */
    @Modifying
    @Query("DELETE FROM account_recovery_code WHERE account_id = :accountId")
    int deleteAllFor(@Param("accountId") UUID accountId);
}
