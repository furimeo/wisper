import type {MemberRole, OrganizationStatus} from '@/shell'

/**
 * The `org` package's records, as they arrive on a page.
 *
 * Every interface here is one Java record in `lhqm.furimeo.wisper.org`, with its
 * components in declaration order and its derived accessors marked.
 * `docs/contracts/pages.md` §5 is the source; a field that is not there is not sent, and
 * adding one here does not make it appear.
 *
 * `MemberRole`, `OrganizationStatus` and `OrganizationSummary` are declared by the shell,
 * which needs them for the organization switcher on every page. They are re-exported
 * rather than redeclared - two definitions of one union is how one of them ends up
 * missing a value.
 */
export type {MemberRole, OrganizationStatus, OrganizationSummary} from '@/shell'

/** `org.QuotaResource`. Every metered thing on the platform. */
export type QuotaResource =
  | 'PROJECT'
  | 'SERVICE'
  | 'DOMAIN'
  | 'MANAGED_DATABASE'
  | 'CRON_TASK'
  | 'MEMBER'
  | 'API_TOKEN'
  | 'VOLUME_BYTES'
  | 'MEMORY_BYTES'
  | 'CPU_MILLICORES'
  | 'BACKUP_BYTES'
  | 'RESTORE_POINT'
  | 'DEPLOYMENTS_PER_DAY'

/** `org.QuotaAllowance.QuotaSource`. Where the number in force came from. */
export type QuotaSource = 'ORGANIZATION_OVERRIDE' | 'PLAN' | 'UNSET'

/** `org.Membership`. The viewer's own standing in one organization. */
export interface Membership {
  organizationId: string
  accountId: string
  role: MemberRole
  /** Derived from `isOwner()`. */
  owner: boolean
}

/** `org.MemberView`. One row of the member list. */
export interface MemberView {
  id: string
  accountId: string
  email: string
  displayName: string
  role: MemberRole
  /** False while the invitation is outstanding. */
  accepted: boolean
  invitedAt: string | null
  acceptedAt: string | null
}

/**
 * `org.QuotaAllowance`. One limit, what is used against it, and where it came from.
 *
 * `limit` is never "unlimited": an unset quota is zero, deliberately, so a plan somebody
 * forgot to seed is a tenant that cannot create anything rather than a tenant with no
 * ceiling at all.
 */
export interface QuotaAllowance {
  resource: QuotaResource
  limit: number
  used: number
  source: QuotaSource
}

/** `org.Organization`, the aggregate, passed to the admin screens as-is. */
export interface Organization {
  id: string
  name: string
  slug: string
  planId: string
  status: OrganizationStatus
  suspendedAt: string | null
  suspensionReason: string | null
  createdAt: string
  updatedAt: string
  version: number
  /** Derived from `isSuspended()`. */
  suspended: boolean
}

/**
 * `org.Plan`, the aggregate.
 *
 * `isDefault` keeps its `is`: Jackson names a record property after the component and
 * only strips the prefix from a *derived* accessor, which is why `isSelectable()` arrives
 * as `selectable` and this one does not.
 */
export interface Plan {
  id: string
  code: string
  name: string
  description: string | null
  isDefault: boolean
  archivedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
  /** Derived from `isSelectable()`: whether a new organization may be opened on it. */
  selectable: boolean
}

/** `org.QuotaOverride`, the aggregate. One tenant's exception to its plan. */
export interface QuotaOverride {
  id: string
  organizationId: string
  resource: QuotaResource
  limitValue: number
  reason: string
  expiresAt: string | null
  grantedByAccountId: string | null
  createdAt: string
  updatedAt: string
  version: number
}

/** `org.AdminPlanController.PlanLimit`. One line of a plan's limits table. */
export interface PlanLimit {
  resource: QuotaResource
  limit: number
  /** Whether a `quota` row exists - "set to zero" against "never set". */
  explicit: boolean
}
