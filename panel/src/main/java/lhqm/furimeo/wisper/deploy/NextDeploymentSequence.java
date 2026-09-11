package lhqm.furimeo.wisper.deploy;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Allocates the per-service deployment number the customer sees.
 *
 * <p>{@code max(sequence) + 1}, which is only correct if two deploys of the same service
 * cannot read the same maximum. The {@code deployment_service_sequence_key} unique index
 * guarantees they cannot both <em>keep</em> it, but the way that resolves without a lock
 * is one of the two transactions dying on a constraint violation - and a customer who
 * pressed deploy twice getting a stack trace for the second press is not "resolving".
 *
 * <p>So the allocation takes a transaction-scoped advisory lock keyed on the service
 * first. PostgreSQL releases it at commit or rollback with no unlock call to forget, and
 * it touches no row, so it cannot deadlock against a settings save holding the
 * {@code service} row - which a {@code SELECT ... FOR UPDATE} on that row could.
 *
 * <p>Two different services whose ids happen to hash alike will serialise against each
 * other. That costs a few milliseconds on a collision that happens once in four billion
 * and is never wrong, because the lock is an optimisation over a unique index that is
 * still there underneath.
 */
@Component
public class NextDeploymentSequence {

    /**
     * The first half of the advisory lock key: {@code "depl"} in ASCII.
     *
     * <p>PostgreSQL advisory locks share one namespace across the whole database, so the
     * class distinguishes this lock from anything else in the panel that takes one.
     */
    private static final int LOCK_CLASS = 0x6465_706C;

    private static final String LOCK =
            "SELECT true FROM pg_advisory_xact_lock(:lockClass, :lockKey)";

    private static final String NEXT = """
            SELECT COALESCE(MAX(sequence), 0) + 1 FROM deployment
             WHERE service_id = :serviceId
            """;

    private final JdbcClient jdbc;

    public NextDeploymentSequence(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The next number for this service, with the allocation held until the caller's
     * transaction ends.
     *
     * @throws IllegalStateException if called outside a transaction, because an advisory
     *         transaction lock taken outside one is released immediately and the number
     *         it protects is stale before it is returned
     */
    public long forService(UUID serviceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A deployment sequence must be allocated inside the "
                    + "transaction that inserts the row; outside one the advisory lock is "
                    + "released before the insert happens.");
        }
        jdbc.sql(LOCK)
                .param("lockClass", LOCK_CLASS)
                .param("lockKey", serviceId.hashCode())
                .query(Boolean.class)
                .single();
        return jdbc.sql(NEXT)
                .param("serviceId", serviceId)
                .query(Long.class)
                .single();
    }
}
