package lhqm.furimeo.wisper.org;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Looks up the handful of account columns this package is entitled to read.
 *
 * <p>Explicit SQL through {@link JdbcClient} rather than a second Spring Data aggregate
 * for {@code account}: two mapped aggregates over one table, in two packages, is two
 * places for a column to be written from, and {@code org} must never write this one.
 * Every statement here is a {@code SELECT}.
 */
@Component
public class FindAccount {

    private static final String COLUMNS =
            "id, email, display_name, status = 'ACTIVE' AS active";

    private final JdbcClient jdbc;

    public FindAccount(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * By address, which is how an invitation names somebody.
     *
     * <p>The argument is lower-cased first: {@code account.email} is stored lower-cased
     * and a CHECK keeps it that way, so a customer typing {@code Ada@Example.com} into
     * the invite box has to find the same row.
     */
    public Optional<AccountRef> byEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE email = :email")
                .param("email", email.trim().toLowerCase(Locale.ROOT))
                .query(FindAccount::map)
                .optional();
    }

    public Optional<AccountRef> byId(UUID accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE id = :id")
                .param("id", accountId)
                .query(FindAccount::map)
                .optional();
    }

    /** For a caller holding an id that came out of a foreign key and must resolve. */
    public AccountRef requireById(UUID accountId) {
        return byId(accountId).orElseThrow(() -> NotFoundException.of("account", accountId));
    }

    /**
     * Several at once, keyed by id, so a member list is two queries rather than one per
     * row.
     */
    public Map<UUID, AccountRef> byIds(List<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE id IN (:ids)")
                .param("ids", accountIds)
                .query(FindAccount::map)
                .list()
                .stream()
                .collect(Collectors.toMap(AccountRef::id, account -> account));
    }

    private static AccountRef map(ResultSet row, int rowNumber) throws SQLException {
        return new AccountRef(row.getObject("id", UUID.class), row.getString("email"),
                row.getString("display_name"), row.getBoolean("active"));
    }
}
