/**
 * The `audit` package's records, as they arrive on `/admin/audit`.
 *
 * Every interface here is one Java record in `lhqm.furimeo.wisper.audit`, with its
 * components in declaration order and its derived accessors marked.
 * `docs/contracts/pages.md` §5 is the source.
 */

/** `audit.AuditActorKind`. Who did it. */
export type AuditActorKind = 'ACCOUNT' | 'API_TOKEN' | 'NODE' | 'SYSTEM'

/** `audit.AuditOutcome`. How it ended. `DENIED` is why this is not a boolean. */
export type AuditOutcome = 'SUCCEEDED' | 'FAILED' | 'DENIED'

/**
 * `audit.AuditLogEntry`. One row as a person reads it.
 *
 * `actorLabel` survives the account being deleted, which is the case this record exists
 * for: the trail has to stay readable after the person in it is gone.
 *
 * `detail` is one short sentence and never a secret value - the writer names the field
 * that changed, not what it changed to.
 */
export interface AuditLogEntry {
  id: string
  occurredAt: string
  organizationId: string | null
  actorKind: AuditActorKind
  actorAccountId: string | null
  nodeId: string | null
  actorLabel: string
  /** A dotted action from the vocabulary in `panel-ports.md` §2.4, such as `service.stop`. */
  action: string
  targetKind: string | null
  targetId: string | null
  targetLabel: string | null
  outcome: AuditOutcome
  remoteAddress: string | null
  requestId: string | null
  detail: string | null
  /** Derived from `isRefusal()`: the outcome was DENIED. */
  refusal: boolean
}

/**
 * `audit.AuditLogPage`. One page of the trail plus enough to draw the pager.
 *
 * `total` is a real count over the same predicates rather than an estimate, because the
 * question this screen answers is often "did this happen at all" and "about 4,000" is a
 * worse answer than the truth. `hasNext()`, `hasPrevious()`, `firstRow()` and `lastRow()`
 * exist in Java but are not bean-named, so they do not arrive - the pager works them out
 * from these four numbers.
 */
export interface AuditLogPage {
  entries: AuditLogEntry[]
  total: number
  offset: number
  pageSize: number
}

/**
 * The sanitised query, echoed back so the form redraws with what is actually in effect.
 *
 * Sanitised and not raw: a value the search dropped - an unknown action, a mistyped date -
 * must not stay in the box claiming to be filtering anything.
 */
export interface AuditFilter {
  organizationId: string | null
  accountId: string | null
  nodeId: string | null
  action: string | null
  targetKind: string | null
  targetId: string | null
  outcome: AuditOutcome | null
  from: string | null
  to: string | null
  limit: number
  offset: number
}
