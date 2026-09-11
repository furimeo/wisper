/**
 * The `service` package's records and vocabularies, as they arrive on a page.
 *
 * Every interface here is one Java record in `lhqm.furimeo.wisper.service`, components in
 * declaration order, derived accessors marked. `docs/contracts/pages.md` §4 and §5 are the
 * source: a field that is not there is not sent, and adding one here does not make it
 * appear. The unions are Java enums serialised by `name()`, so a typo is a compile error
 * rather than a `<select>` the CHECK constraint refuses.
 *
 * `features/files`, `features/stats`, `features/deploy` and `features/domain` all read a
 * service; they import from here rather than redeclaring, because two definitions of
 * `ServiceKind` is how one of them ends up missing `SITE`.
 */

/** `service.ServiceKind`. A container the platform runs, or files it serves. */
export type ServiceKind = 'APP' | 'SITE'

/** `service.DesiredState`. What the customer asked for; the panel owns this. */
export type DesiredState = 'RUNNING' | 'STOPPED'

/** `service.RuntimeIsolation`. gVisor, or the documented escape hatch. */
export type RuntimeIsolation = 'RUNSC' | 'RUNC'

/** `service.RestartPolicy`. */
export type RestartPolicy = 'ALWAYS' | 'ON_FAILURE' | 'NEVER'

/** `service.BuildPreset`. How a site is turned into a directory of files. */
export type BuildPreset = 'STATIC' | 'NODE' | 'HUGO' | 'ASTRO' | 'JEKYLL' | 'CUSTOM'

/** `service.ConcurrencyPolicy`. What happens when a run is still going. */
export type ConcurrencyPolicy = 'ALLOW' | 'FORBID' | 'REPLACE'

/** What a node last said about a workload. The node owns this; the panel never writes it. */
export type ReportedWorkloadState =
  | 'PENDING'
  | 'CREATING'
  | 'RUNNING'
  | 'STOPPED'
  | 'CRASHED'
  | 'DEGRADED'
  | 'UNKNOWN'

/** The health probe's verdict, as reported. */
export type WorkloadHealth = 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN'

/**
 * `service.ServiceView`. A service as a screen may see it.
 *
 * It is not the aggregate: `Service` carries the webhook secret and the repository
 * credential, both encrypted, and both would land in the page's props if a controller
 * passed it whole. `hasRepositoryCredential` is the only thing said about the credential,
 * and it is what the settings form needs to explain that an empty box means "unchanged".
 */
export interface ServiceView {
  id: string
  projectId: string
  name: string
  slug: string
  kind: ServiceKind
  desiredState: DesiredState
  runtimeIsolation: RuntimeIsolation
  isolationReason: string | null

  image: string | null
  imageDigest: string | null
  /** Joined for display, not argv. `CommandLine.parse` of what is shown is what was stored. */
  command: string | null
  entrypoint: string | null
  workingDir: string | null
  containerPort: number | null
  healthCheckPath: string | null
  healthCheckIntervalSeconds: number
  restartPolicy: RestartPolicy

  buildPreset: BuildPreset | null
  buildCommand: string | null
  buildOutputDir: string | null
  keepReleases: number

  repositoryUrl: string | null
  repositoryBranch: string | null
  hasRepositoryCredential: boolean
  autoDeploy: boolean

  cpuMillicores: number
  memoryBytes: number
  diskBytes: number
  pidsLimit: number

  requiredTags: string[]
  archivedAt: string | null
  createdAt: string
  updatedAt: string

  /** Derived from `isApp()`, `isSite()`, `isArchived()`. */
  app: boolean
  site: boolean
  archived: boolean
}

/**
 * `service.ServiceSummary`. One row of a project's list, and the fact half of the
 * overview.
 *
 * `reportedState` and `health` are strings the node sent and are null until it has said
 * anything at all. That null is a real state - "not placed yet" - and drawing an empty
 * pill for it is how a customer learns to distrust the pills that mean something.
 */
export interface ServiceSummary {
  id: string
  projectId: string
  name: string
  slug: string
  kind: ServiceKind
  desiredState: DesiredState
  archivedAt: string | null
  image: string | null
  buildPreset: BuildPreset | null
  nodeId: string | null
  reportedState: ReportedWorkloadState | null
  health: WorkloadHealth | null
  reportedAt: string | null
  createdAt: string
  /** Derived from `isArchived()`, `isDrifting()`, `isWantedRunning()`. */
  archived: boolean
  drifting: boolean
  wantedRunning: boolean
}

/** `service.ServiceLocation`. Enough of a service for the screens that talk to its node. */
export interface ServiceLocation {
  serviceId: string
  projectId: string
  organizationId: string
  nodeId: string | null
  slug: string
  name: string
  kind: ServiceKind
  /** Derived from `isPlaced()`: whether a node is holding it right now. */
  placed: boolean
}

/** `service.EnvVar`, the aggregate. Holds no secret, which is why it is passed as-is. */
export interface EnvVar {
  id: string
  serviceId: string
  name: string
  value: string
  buildTime: boolean
  createdAt: string
  updatedAt: string
  version: number
}

/** `service.SecretView`. Never carries `value`: the query does not select the column. */
export interface SecretView {
  id: string
  name: string
  buildTime: boolean
  lastChangedAt: string | null
  createdAt: string
}

/** `service.Volume`, the aggregate. The measured fields are null until a node reports. */
export interface Volume {
  id: string
  serviceId: string
  name: string
  mountPath: string
  sizeBytes: number
  readOnly: boolean
  backupEnabled: boolean
  usedBytes: number | null
  usedBytesMeasuredAt: string | null
  inodeCount: number | null
  hostPath: string | null
  reportedState: string | null
  lastError: string | null
  createdAt: string
  updatedAt: string
  version: number
}

/** `service.CronTaskView`. A scheduled command, with how the last run went. */
export interface CronTaskView {
  id: string
  name: string
  schedule: string
  timezone: string | null
  /** argv joined for display. */
  command: string
  enabled: boolean
  timeoutSeconds: number
  concurrencyPolicy: ConcurrencyPolicy
  lastRunAt: string | null
  lastFinishedAt: string | null
  lastExitCode: number | null
  lastDurationMs: number | null
  lastError: string | null
  nextRunAt: string | null
  running: boolean
}

/**
 * `service.ServiceDraft`, as `ServiceDraft.defaults()` sends it to the new-service form.
 *
 * The record's compact constructor fills in every limit, so `restartPolicy`,
 * `runtimeIsolation`, `keepReleases`, `healthCheckIntervalSeconds` and the four resource
 * numbers arrive set even on an empty draft - what the form shows and what an untouched
 * box produces are the same value by construction. `kind` is the exception: nothing
 * defaults it, because choosing between an app and a site is the one decision the form
 * cannot make for the customer.
 */
export interface ServiceDraft {
  name: string | null
  slug: string | null
  kind: ServiceKind | null
  image: string | null
  command: string[]
  entrypoint: string[]
  workingDir: string | null
  containerPort: number | null
  healthCheckPath: string | null
  healthCheckIntervalSeconds: number
  restartPolicy: RestartPolicy
  buildPreset: BuildPreset | null
  buildCommand: string | null
  buildOutputDir: string | null
  keepReleases: number
  repositoryUrl: string | null
  repositoryBranch: string | null
  repositoryCredential: string | null
  autoDeploy: boolean
  cpuMillicores: number
  memoryBytes: number
  diskBytes: number
  pidsLimit: number
  requiredTags: string[]
  runtimeIsolation: RuntimeIsolation
  isolationReason: string | null
}

/** The tab badges on `service/ServiceOverview`, straight off `Map<String, Long>`. */
export interface ServiceCounts {
  envVars: number
  secrets: number
  volumes: number
  scheduledTasks: number
}
