package lhqm.furimeo.wisper.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Reads and writes {@code account}.
 *
 * <p>Every lookup by address goes through {@link #findByEmail}, and the address is
 * normalised by {@link Account#normaliseEmail} before it gets here - the column has a
 * lower-case CHECK on it, so a query with a capital letter in it silently matches nothing
 * rather than failing.
 */
public interface AccountRepository extends ListCrudRepository<Account, UUID> {

    /** The sign-in lookup. The address must already be lower-cased. */
    Optional<Account> findByEmail(String email);

    /** Whether that address is taken, for the registration form's field error. */
    boolean existsByEmail(String email);

    /** The admin account list, in an order that does not change between page loads. */
    @Query("SELECT * FROM account ORDER BY email")
    List<Account> findAllOrderedByEmail();

    /**
     * How many operators are left who can still sign in.
     *
     * <p>Read before suspending or demoting one. A platform with no reachable admin has
     * no way back in short of editing the database by hand, and the person doing it is
     * usually the person who just locked themselves out.
     */
    @Query("SELECT count(*) FROM account WHERE platform_role = 'ADMIN' AND status = 'ACTIVE'")
    long countActiveAdmins();
}
