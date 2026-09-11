/**
 * The shapes `AdminJobController` puts in the model.
 *
 * <p>Typed against the Java records, which are the binding source. Two things they carry
 * do not reach here: `FailedJob.domain()` and `FailedJob.hasEverSucceeded()` have no
 * `get`/`is` prefix and are not record components, so Jackson never serialises them. Both
 * are one line and are recomputed below rather than being asked for on the server, which
 * would mean changing a record to satisfy a list view. `isActionable()` and `isHealthy()`
 * do serialise, as `actionable` and `healthy`.
 */

/** `JobQueueSummary` - the four counters above the list. */
export interface JobQueueSummary {
  queued: number
  /** Rows whose execution time has passed. A number that stays high means the workers
   * cannot keep up, or are not running at all. */
  due: number
  running: number
  failing: number
  /** `isHealthy()`: nothing is failing. */
  healthy: boolean
}

/** `FailedJob` - one row of `scheduled_tasks` with a current failure streak. */
export interface FailedJob {
  /** `<domain>-<verb>`, e.g. `deploy-build`. */
  taskName: string
  /** The id of the row the job acts on. */
  instanceId: string
  /** When it will next be attempted; pushed further out by every failure. */
  executionTime: string
  consecutiveFailures: number
  lastFailure: string | null
  /** Null when it has never worked - a different problem from "broken since Tuesday". */
  lastSuccess: string | null
  /** A worker is holding it right now, so neither button applies. */
  picked: boolean
  pickedBy: string | null
  lastHeartbeat: string | null
  /** `isActionable()`: not picked. */
  actionable: boolean
}

/** `FailedJobPage`. `hasNext()`/`hasPrevious()` are not serialised; `Pagination` derives them. */
export interface FailedJobPage {
  jobs: FailedJob[]
  total: number
  offset: number
  pageSize: number
  /** `isEmpty()`. */
  empty: boolean
}

/** The package that owns the work, from the task name's prefix - `FailedJob.domain()`. */
export function domainOf(job: FailedJob): string {
  const dash = job.taskName.indexOf('-')
  return dash < 0 ? job.taskName : job.taskName.slice(0, dash)
}

/** `FailedJob.hasEverSucceeded()`. */
export function hasEverSucceeded(job: FailedJob): boolean {
  return job.lastSuccess !== null
}
