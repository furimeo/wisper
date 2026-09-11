/**
 * The `backup` package's records, as they arrive on a page.
 *
 * Every interface here is one Java record in `lhqm.furimeo.wisper.backup`, with its
 * components in declaration order and its derived accessors marked.
 * `docs/contracts/pages.md` §5 is the source.
 */

/** `backup.BackupTargetKind`. The two things worth copying. */
export type BackupTargetKind = 'VOLUME' | 'DATABASE'

/** `backup.BackupRunStatus`. How the last run of a policy ended. */
export type BackupRunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED'

/** `backup.DestinationKind`. Offsite, or on the node itself. */
export type DestinationKind = 'S3' | 'LOCAL'

/** `backup.RestorePointState`. */
export type RestorePointState = 'RUNNING' | 'AVAILABLE' | 'FAILED' | 'EXPIRED' | 'DELETED'

/** `backup.RestorePointTrigger`. Why this snapshot exists. */
export type RestorePointTrigger = 'SCHEDULED' | 'MANUAL' | 'PRE_RESTORE'

/** `backup.RestoreMode`. Overwrite the live data, or prove the archive restores. */
export type RestoreMode = 'IN_PLACE' | 'VERIFY'

/** `backup.RestoreState`. */
export type RestoreState = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

/** `backup.BackupView`. One policy: what is copied, how often, and what came of it. */
export interface BackupView {
  id: string
  name: string
  targetKind: BackupTargetKind
  targetLabel: string
  destinationId: string
  destinationName: string
  destinationKind: DestinationKind
  /** A cron expression, or null for a policy that only runs when somebody presses it. */
  schedule: string | null
  timezone: string | null
  enabled: boolean
  retentionCount: number
  retentionDays: number
  lastRunAt: string | null
  lastStatus: BackupRunStatus | null
  lastError: string | null
  nextRunAt: string | null
  snapshotCount: number
  latestSnapshotAt: string | null
  storedBytes: number
  /** Derived from `isScheduled()`: enabled and carrying a cron expression. */
  scheduled: boolean
}

/** `backup.DestinationView`. Where snapshots are pushed. Never the secret key. */
export interface DestinationView {
  id: string
  name: string
  kind: DestinationKind
  /** True for an operator's row, shared by every tenant and not editable from /backups. */
  platformWide: boolean
  editable: boolean
  enabled: boolean
  endpoint: string | null
  region: string | null
  bucket: string | null
  pathPrefix: string | null
  accessKeyId: string | null
  storageClass: string | null
  localPath: string | null
  lastCheckedAt: string | null
  lastCheckError: string | null
  policyCount: number
  snapshotCount: number
  storedBytes: number
  /** Derived from `isProvenReachable()`: checked, and the check passed. */
  provenReachable: boolean
  /** Derived from `isRemovable()`: editable, with no policy and no snapshot on it. */
  removable: boolean
}

/** `backup.RestorePointView`. One snapshot, and whether anybody has proved it restores. */
export interface RestorePointView {
  id: string
  targetKind: BackupTargetKind
  targetLabel: string
  state: RestorePointState
  trigger: RestorePointTrigger
  sizeBytes: number | null
  encrypted: boolean
  objectKey: string
  startedAt: string
  finishedAt: string | null
  expiresAt: string | null
  errorMessage: string | null
  destinationName: string
  backupName: string | null
  lastVerifiedAt: string | null
  activeRestores: number
  restorable: boolean
  /** Derived from `isProven()`: it has been restored somewhere disposable at least once. */
  proven: boolean
}

/** `backup.RestoreRunView`. One restore, with the node's own progress log. */
export interface RestoreRunView {
  id: string
  restorePointId: string
  targetLabel: string
  snapshotTakenAt: string | null
  mode: RestoreMode
  state: RestoreState
  nodeId: string | null
  startedAt: string | null
  finishedAt: string | null
  bytesRestored: number | null
  errorMessage: string | null
  log: string | null
  /** The snapshot taken of the live data before it was overwritten, for an in-place run. */
  safetyRestorePointId: string | null
  requestedBy: string | null
  /** Derived from `isFinished()`. */
  finished: boolean
}

/** `backup.BackupTargetOption`. One volume or database the create form may point at. */
export interface BackupTargetOption {
  kind: BackupTargetKind
  id: string
  label: string
  /** False when the platform cannot back it up right now - unplaced, or switched off. */
  eligible: boolean
  policyCount: number
}
