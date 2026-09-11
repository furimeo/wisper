package lhqm.furimeo.wisper.audit;

import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

/**
 * Writes to {@code audit_log}.
 *
 * <p>{@link CrudRepository} rather than {@code ListCrudRepository}, and deliberately not
 * extended with finders: reading the trail is filtered, paged and ordered, and that is
 * {@link SearchAuditLog}'s single statement rather than nine derived queries whose
 * combinations nobody has tried.
 *
 * <p>The table is append-only. Nothing in this package updates a row and nothing deletes
 * one, so the inherited {@code delete*} methods are the only removal path that exists and
 * no code calls them. If a retention sweep is ever wanted, it needs a task name in
 * {@code docs/contracts/panel-ports.md} §2.3 first; inventing one here would be a job
 * nobody agreed to.
 */
public interface AuditLogRepository extends CrudRepository<AuditLog, UUID> {
}
