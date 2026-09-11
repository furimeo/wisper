/**
 * The `project` package's records, as they arrive on a page.
 *
 * Two shapes and no more: the aggregate, which the overview and settings screens are given
 * whole because it holds nothing secret, and the summary the dashboard is built from.
 * `docs/contracts/pages.md` §5 is the source.
 */

/** `project.Project`, the aggregate, passed as-is. */
export interface Project {
  id: string
  organizationId: string
  name: string
  slug: string
  /** Never null: the compact constructor turns a missing description into an empty string. */
  description: string | null
  archivedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
  /** Derived from `isArchived()`. */
  archived: boolean
}

/**
 * `project.ProjectSummary`. One card on the dashboard.
 *
 * It carries `organizationName` because the dashboard lists every tenant the account can
 * reach rather than filtering to the current one - a project that vanishes when the
 * switcher and the session disagree is a project the customer reports as deleted.
 */
export interface ProjectSummary {
  id: string
  organizationId: string
  organizationName: string
  name: string
  slug: string
  description: string | null
  archivedAt: string | null
  createdAt: string
  serviceCount: number
  runningCount: number
  /** Derived from `isArchived()` and `isEmpty()`. */
  archived: boolean
  empty: boolean
}
