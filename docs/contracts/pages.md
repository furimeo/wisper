# Pages

Binding. See [README.md](README.md).

The Inertia contract: every page component the panel can render, the exact props its
controller passes, and the TypeScript those props deserialise to. Frontend agents build
exactly these names and these props; panel agents emit exactly these names and these
props. A mismatch here is a page that renders `undefined`, and neither side finds out
until somebody opens it.

Route ownership and the form/redirect conventions are in
[`panel-http.md`](panel-http.md). This file is the payload.

---

## 1. How a page is named

A controller returns `"<feature>/<PageName>"`. `InertiaViewResolver` turns that into a
component name, and `resolvePage.ts` loads
`frontend/src/features/<feature>/<PageName>Page.tsx`.

```
"deploy/DeploymentList"   ->  features/deploy/DeploymentListPage.tsx
"files/FileManager"       ->  features/files/FileManagerPage.tsx
"node/AdminNodeDetail"    ->  features/node/AdminNodeDetailPage.tsx
```

The feature folder is the Java package. A file that does not end in `Page.tsx` is not
routable, which is what stops a helper component becoming a page by accident. A view name
with no file throws a named error at load time rather than rendering blank - see
`resolvePage.ts`.

---

## 2. How Java becomes JSON

One `ObjectMapper`, Spring Boot's auto-configured Jackson 3, no annotations anywhere in
the panel. The rules are therefore short and total.

| Java | JSON | TypeScript |
|---|---|---|
| `UUID` | string | `string` |
| `Instant` | ISO-8601 with nanoseconds and a `Z`: `"2026-09-11T10:15:30.123456Z"` | `string` |
| `enum` | its `name()` | a string-literal union, §4 |
| `long`, `int`, `Long`, `Integer` | number | `number` (nullable box -> `number \| null`) |
| `boolean`, `Boolean` | boolean | `boolean` (nullable box -> `boolean \| null`) |
| `String[]`, `List<T>` | array | `T[]` |
| `Map<K,V>` | object | `Record<string, V>` |
| `record` | object, components in declaration order | an `interface` |
| `null` model attribute | `null` | `T \| null` |

Two consequences that are easy to trip over:

1. **A record's derived accessors are props too.** Jackson serialises any zero-argument
   `isXxx()` on a record alongside its components, as `xxx`. `ServiceSummary` therefore
   arrives with `archived`, `drifting` and `wantedRunning` on it. Methods that are not
   bean-named - `canWrite()`, `remaining()`, `label()` - are **not** serialised. Every
   derived prop is listed in the interfaces in §5; they are part of the contract, and
   removing one from the Java side breaks a page.
2. **There is no `@JsonIgnore` anywhere.** A record put in a model is serialised whole.
   That is why the pages are given `*View` records rather than the Spring Data JDBC
   aggregates - `Account` carries `passwordHash`, `Service` carries `webhookSecret`. The
   aggregates that *are* passed directly are `Project`, `Organization`, `Plan`,
   `QuotaOverride`, `EnvVar`, `Volume` and `DeploymentLog`, none of which holds a secret.

### Partial reloads

`InertiaPage` honours `X-Inertia-Partial-Data` and `X-Inertia-Partial-Except` when
`X-Inertia-Partial-Component` matches the page being rendered. `router.reload({only: [...]})`
therefore works on every page here. The controller still does all of its work; only the
serialised set is narrowed.

---

## 3. Shared props

Every page gets these, on top of whatever its controller put in the model. Two
`SharedPropsContributor` beans and no more; a controller's model wins on a key collision.

| Prop | From | Always present |
|---|---|---|
| `errors` | `web.SharedProps` | yes, `{}` when empty |
| `flash` | `web.SharedProps` | yes, `{}` when empty |
| `account` | `auth.SignedInAccountProps` | yes, `null` when signed out |
| `organizations` | `org.CurrentOrganizationProps` (`@Order(20)`) | yes, `[]` when signed out |
| `organization` | `org.CurrentOrganizationProps` | yes, `null` when there is none |

Nothing here is ever *absent*. A page that has to write `account?.email` in one place and
`account.email` in another eventually gets it wrong where nobody looked.

`organization` is chosen as: the `{organizationId}` in the URL, else the one remembered in
the session, else the first accepted membership, else `null`. An outstanding invitation is
listed in `organizations` but is never made current.

This is the declaration `frontend/src/inertia/sharedProps.ts` must carry:

```ts
export type FieldErrors = Record<string, string>
export type FlashMessages = {success?: string; error?: string}

export interface SharedProps {
  errors: FieldErrors
  flash: FlashMessages
  account: SignedInAccount | null
  organizations: OrganizationSummary[]
  organization: OrganizationSummary | null
  [key: string]: unknown
}
```

### One-shot flash props

`RedirectAttributes.addFlashAttribute` puts a value in the *model* of the page that is
redirected to, so these arrive as ordinary top-level props exactly once. They are typed
optional on the page that receives them.

| Prop | Type | Written by | Read on |
|---|---|---|---|
| `issuedToken` | `string` | `POST /settings/tokens` | `auth/ApiTokens` |
| `issuedTokenName` | `string` | `POST /settings/tokens` | `auth/ApiTokens` |
| `enrolment` | `TwoFactorEnrolment` | `POST /settings/security/two-factor/begin` | `auth/Security` |
| `recoveryCodes` | `string[]` | 2FA confirm, recovery-code regeneration | `auth/Security` |
| `connection` | `ConnectionString` | `POST /databases/{id}/reveal`, `.../password` | `database/DatabaseDetail` |
| `bootstrapToken` | `string` | `POST /admin/nodes`, `POST /admin/nodes/{id}/tokens` | `node/AdminNodeDetail` |
| `bootstrapTokenExpiresAt` | `string` | as above | `node/AdminNodeDetail` |
| `installCommand` | `string` | as above | `node/AdminNodeDetail` |
| `installChecksum` | `string` | as above | `node/AdminNodeDetail` |

Each is shown once and cannot be shown again. That is the whole point of them: a token the
page could re-fetch is a token stored somewhere it could be re-fetched from.

---

## 4. Enum vocabularies

Every one of these is a Java enum serialised by name. Declare them as string-literal
unions so a typo is a compile error.

```ts
type PlatformRole      = 'ADMIN' | 'CUSTOMER'
type AccountStatus     = 'ACTIVE' | 'SUSPENDED'
type MemberRole        = 'OWNER' | 'ADMIN' | 'DEVELOPER' | 'VIEWER'
type OrganizationStatus = 'ACTIVE' | 'SUSPENDED'
type QuotaSource       = 'ORGANIZATION_OVERRIDE' | 'PLAN' | 'UNSET'
type QuotaResource     =
  | 'PROJECT' | 'SERVICE' | 'DOMAIN' | 'MANAGED_DATABASE' | 'CRON_TASK' | 'MEMBER'
  | 'API_TOKEN' | 'VOLUME_BYTES' | 'MEMORY_BYTES' | 'CPU_MILLICORES' | 'BACKUP_BYTES'
  | 'RESTORE_POINT' | 'DEPLOYMENTS_PER_DAY'
// The wire form, which is what api_token.scopes stores and what the form submits.
// NOT the Java constant name: `ApiScope.PROJECTS_READ.wireName()` is "projects:read".
type ApiScope =
  | 'projects:read' | 'projects:write' | 'services:read' | 'services:write'
  | 'deployments:read' | 'deployments:write' | 'domains:read' | 'domains:write'
  | 'databases:read' | 'databases:write' | 'files:read' | 'files:write'
  | 'backups:read' | 'backups:write' | 'metrics:read' | 'nodes:read' | 'nodes:write'

type ServiceKind       = 'APP' | 'SITE'
type DesiredState      = 'RUNNING' | 'STOPPED'
type RuntimeIsolation  = 'RUNSC' | 'RUNC'
type RestartPolicy     = 'ALWAYS' | 'ON_FAILURE' | 'NEVER'
type BuildPreset       = 'STATIC' | 'NODE' | 'HUGO' | 'ASTRO' | 'JEKYLL' | 'CUSTOM'
type ConcurrencyPolicy = 'ALLOW' | 'FORBID' | 'REPLACE'
type ReportedWorkloadState =
  'PENDING' | 'CREATING' | 'RUNNING' | 'STOPPED' | 'CRASHED' | 'DEGRADED' | 'UNKNOWN'
type WorkloadHealth    = 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN'
type PlacementState    = 'PLANNED' | 'ACTIVE' | 'DRAINING' | 'RELEASED'

type DeploymentStatus  =
  | 'QUEUED' | 'ASSIGNED' | 'BUILDING' | 'PUBLISHING' | 'SUCCEEDED' | 'FAILED'
  | 'CANCELLED' | 'SUPERSEDED'
type DeploymentTrigger = 'MANUAL' | 'GIT_PUSH' | 'ROLLBACK' | 'API' | 'SCHEDULED'
type DeploymentSource  = 'GIT' | 'ARCHIVE' | 'IMAGE'
type DeploymentLogStream = 'STDOUT' | 'STDERR' | 'SYSTEM'

type DomainKind          = 'PRIMARY' | 'ALIAS' | 'WILDCARD'
type DomainTlsMode       = 'ON_DEMAND' | 'STATIC' | 'OFF'
type DomainVerification  = 'PENDING' | 'VERIFIED' | 'FAILED'
type CertificateState    = 'PENDING' | 'ISSUED' | 'RENEWING' | 'FAILED' | 'REVOKED'

type EngineKind          = 'POSTGRES' | 'MYSQL'
type EngineMode          = 'SHARED' | 'DEDICATED'
type EngineDesiredState  = 'RUNNING' | 'STOPPED'
type ManagedDatabaseState = 'PENDING' | 'READY' | 'SUSPENDED' | 'FAILED' | 'DELETING'

type BackupTargetKind    = 'VOLUME' | 'DATABASE'
type BackupRunStatus     = 'RUNNING' | 'SUCCEEDED' | 'FAILED'
type DestinationKind     = 'S3' | 'LOCAL'
type RestorePointState   = 'RUNNING' | 'AVAILABLE' | 'FAILED' | 'EXPIRED' | 'DELETED'
type RestorePointTrigger = 'SCHEDULED' | 'MANUAL' | 'PRE_RESTORE'
type RestoreMode         = 'IN_PLACE' | 'VERIFY'
type RestoreState        = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

type NodeLifecycle       = 'CREATED' | 'ENROLLED' | 'DRAINING' | 'DRAINED' | 'SUSPENDED' | 'RETIRED'
type NodeConnectionState = 'DISCONNECTED' | 'CONNECTED' | 'DEGRADED'
type NodeSuspensionReason =
  'DUPLICATE_FINGERPRINT' | 'PROTOCOL_MISMATCH' | 'OPERATOR' | 'CREDENTIAL_REVOKED'
type EnrolmentTokenState = 'LIVE' | 'USED' | 'REVOKED' | 'EXPIRED'
type DoctorSeverity      = 'REQUIRED' | 'ADVISORY' | 'UNSPECIFIED'
type DoctorOutcome       = 'PASS' | 'WARN' | 'FAIL' | 'UNSPECIFIED'

type FileRootKind        = 'VOLUME' | 'SITE'
// The proto enum's own name, prefix and all: the log page passes LogSource.name() through
// unchanged. The two the customer-facing page can show are the only two listed.
type LogSourceName       = 'LOG_SOURCE_CONTAINER' | 'LOG_SOURCE_CRON'
type MetricSource        = 'RAW' | 'HOUR' | 'DAY'
type AuditActorKind      = 'ACCOUNT' | 'API_TOKEN' | 'NODE' | 'SYSTEM'
type AuditOutcome        = 'SUCCEEDED' | 'FAILED' | 'DENIED'
```

`FileRootKind` on the panel side has two values, not the proto's three: the panel never
shows a customer the `UPLOAD_STAGING` root.

`DoctorSeverity` and `DoctorOutcome` arrive as the proto enum name with its
`DOCTOR_SEVERITY_` / `DOCTOR_OUTCOME_` prefix stripped, because `NodeDoctorReport` stores
them as plain strings.

---

## 5. Shared value types

Every interface below is one Java record. Field order is the record's; derived props are
marked. Declare each one in the feature folder that owns it and import across features
rather than redeclaring.

### auth

```ts
interface SignedInAccount {
  id: string; email: string; displayName: string; role: PlatformRole
  platformAdmin: boolean            // derived from isPlatformAdmin()
}

interface AccountProfile {
  id: string; email: string; displayName: string
  platformRole: PlatformRole; status: AccountStatus
  twoFactorEnabled: boolean
  lastLoginAt: string | null; lastLoginAddress: string | null
  passwordChangedAt: string | null; lockedUntil: string | null; createdAt: string
}

interface SessionView {
  id: string; remoteAddress: string | null; userAgent: string | null
  lastSeenAt: string | null; createdAt: string; expiresAt: string
  secondFactorSatisfied: boolean; current: boolean
}

interface ApiTokenView {
  id: string; name: string; maskedValue: string
  organizationId: string | null; scopes: ApiScope[]
  expiresAt: string | null; lastUsedAt: string | null; lastUsedAddress: string | null
  revokedAt: string | null; revokedReason: string | null
  createdAt: string; live: boolean
}

interface AccountOrganization {id: string; name: string; slug: string}

interface TwoFactorEnrolment {secret: string; provisioningUri: string}

interface SignInNotice {tone: 'error' | 'info' | 'success'; message: string}
```

### org

```ts
interface OrganizationSummary {
  id: string; name: string; slug: string
  role: MemberRole; status: OrganizationStatus
  suspensionReason: string | null
  accepted: boolean; invitedAt: string | null
  suspended: boolean                // derived from isSuspended()
}

interface Membership {
  organizationId: string; accountId: string; role: MemberRole
  owner: boolean                    // derived from isOwner()
}

interface MemberView {
  id: string; accountId: string; email: string; displayName: string
  role: MemberRole; accepted: boolean
  invitedAt: string | null; acceptedAt: string | null
}

interface QuotaAllowance {
  resource: QuotaResource; limit: number; used: number; source: QuotaSource
}

interface Organization {                    // the aggregate, passed as-is
  id: string; name: string; slug: string; planId: string
  status: OrganizationStatus; suspendedAt: string | null; suspensionReason: string | null
  createdAt: string; updatedAt: string; version: number
  suspended: boolean                // derived
}

interface Plan {
  id: string; code: string; name: string; description: string | null
  isDefault: boolean                // the record component is literally `isDefault`
  archivedAt: string | null; createdAt: string; updatedAt: string; version: number
  selectable: boolean               // derived from isSelectable()
}

interface QuotaOverride {
  id: string; organizationId: string; resource: QuotaResource; limitValue: number
  reason: string; expiresAt: string | null; grantedByAccountId: string | null
  createdAt: string; updatedAt: string; version: number
}

interface PlanLimit {resource: QuotaResource; limit: number; explicit: boolean}
```

> `Plan.isDefault` is a **record component**, so its JSON key is `isDefault` verbatim.
> Jackson names a record property after the component, and only strips `is`/`get` from
> *derived* accessors - which is why `isDefault` stays and `isSelectable()` becomes
> `selectable`. It is the one place in the panel where a boolean prop keeps its `is`.

### project

```ts
interface Project {                         // the aggregate, passed as-is
  id: string; organizationId: string; name: string; slug: string
  description: string | null; archivedAt: string | null
  createdAt: string; updatedAt: string; version: number
  archived: boolean                 // derived
}

interface ProjectSummary {
  id: string; organizationId: string; organizationName: string
  name: string; slug: string; description: string | null
  archivedAt: string | null; createdAt: string
  serviceCount: number; runningCount: number
  archived: boolean; empty: boolean // derived
}
```

### service

```ts
interface ServiceView {
  id: string; projectId: string; name: string; slug: string
  kind: ServiceKind; desiredState: DesiredState
  runtimeIsolation: RuntimeIsolation; isolationReason: string | null
  image: string | null; imageDigest: string | null
  command: string | null; entrypoint: string | null   // joined for display, not argv
  workingDir: string | null; containerPort: number | null
  healthCheckPath: string | null; healthCheckIntervalSeconds: number
  restartPolicy: RestartPolicy
  buildPreset: BuildPreset | null; buildCommand: string | null
  buildOutputDir: string | null; keepReleases: number
  repositoryUrl: string | null; repositoryBranch: string | null
  hasRepositoryCredential: boolean; autoDeploy: boolean
  cpuMillicores: number; memoryBytes: number; diskBytes: number; pidsLimit: number
  requiredTags: string[]
  archivedAt: string | null; createdAt: string; updatedAt: string
  app: boolean; site: boolean; archived: boolean      // derived
}

interface ServiceSummary {
  id: string; projectId: string; name: string; slug: string
  kind: ServiceKind; desiredState: DesiredState
  archivedAt: string | null; image: string | null; buildPreset: BuildPreset | null
  nodeId: string | null
  reportedState: ReportedWorkloadState | null   // a string; null until a node reports
  health: WorkloadHealth | null
  reportedAt: string | null; createdAt: string
  archived: boolean; drifting: boolean; wantedRunning: boolean   // derived
}

interface ServiceLocation {
  serviceId: string; projectId: string; organizationId: string
  nodeId: string | null; slug: string; name: string; kind: ServiceKind
  placed: boolean                   // derived from isPlaced()
}

interface EnvVar {                          // the aggregate, passed as-is
  id: string; serviceId: string; name: string; value: string; buildTime: boolean
  createdAt: string; updatedAt: string; version: number
}

interface SecretView {                      // never carries `value`
  id: string; name: string; buildTime: boolean
  lastChangedAt: string | null; createdAt: string
}

interface Volume {                          // the aggregate, passed as-is
  id: string; serviceId: string; name: string; mountPath: string
  sizeBytes: number; readOnly: boolean; backupEnabled: boolean
  usedBytes: number | null; usedBytesMeasuredAt: string | null
  inodeCount: number | null; hostPath: string | null
  reportedState: string | null; lastError: string | null
  createdAt: string; updatedAt: string; version: number
}

interface CronTaskView {
  id: string; name: string; schedule: string; timezone: string | null
  command: string                   // argv joined for display
  enabled: boolean; timeoutSeconds: number; concurrencyPolicy: ConcurrencyPolicy
  lastRunAt: string | null; lastFinishedAt: string | null
  lastExitCode: number | null; lastDurationMs: number | null; lastError: string | null
  nextRunAt: string | null; running: boolean
}

interface ServiceDraft {            // the "new service" defaults
  name: string | null; slug: string | null; kind: ServiceKind
  image: string | null; command: string[]; entrypoint: string[]
  workingDir: string | null; containerPort: number | null
  healthCheckPath: string | null; healthCheckIntervalSeconds: number | null
  restartPolicy: RestartPolicy
  buildPreset: BuildPreset | null; buildCommand: string | null
  buildOutputDir: string | null; keepReleases: number | null
  repositoryUrl: string | null; repositoryBranch: string | null
  repositoryCredential: string | null; autoDeploy: boolean
  cpuMillicores: number | null; memoryBytes: number | null
  diskBytes: number | null; pidsLimit: number | null
  requiredTags: string[]
  runtimeIsolation: RuntimeIsolation; isolationReason: string | null
}
```

### deploy

```ts
interface DeploymentTarget {
  serviceId: string; projectId: string; name: string; slug: string; kind: ServiceKind
  repositoryUrl: string | null; repositoryBranch: string | null
  autoDeploy: boolean; keepReleases: number
  webhookPath: string               // relative; prefix with the panel's origin
  deploysFromGit: boolean; acceptsArchive: boolean
}

interface DeploymentSummary {
  id: string; sequence: number
  status: DeploymentStatus; trigger: DeploymentTrigger; source: DeploymentSource
  gitRef: string | null; commitSha: string | null
  commitSubject: string | null; commitAuthor: string | null
  current: boolean; nodeId: string | null; releasePath: string | null
  queuedAt: string; startedAt: string | null; finishedAt: string | null
  durationMs: number | null; errorMessage: string | null
  triggeredBy: string | null; rolledBackFromSequence: number | null
}

interface DeploymentLog {                   // the aggregate, passed as-is
  id: string; deploymentId: string; sequence: number
  stream: DeploymentLogStream; message: string; loggedAt: string; version: number
  fromPanel: boolean                // derived from isFromPanel()
}
```

### domain

`domain` is the one package whose pages do not exist yet. These shapes are the contract it
must satisfy; the tables are `domain` (V18) and `certificate` (V19).

```ts
interface DomainView {
  id: string; serviceId: string; hostname: string
  kind: DomainKind; tlsMode: DomainTlsMode
  verificationState: DomainVerification; verificationToken: string | null
  verifiedAt: string | null; lastCheckedAt: string | null; lastCheckError: string | null
  redirectToHostname: string | null; forceHttps: boolean; targetPort: number | null
  createdAt: string
  certificate: CertificateView | null   // the live one, or null
  serving: boolean                      // the node reported the route as loaded
}

interface CertificateView {
  state: CertificateState; issuer: string | null
  subjectCommonName: string | null; subjectAlternativeNames: string[]
  fingerprintSha256: string | null
  notBefore: string | null; notAfter: string | null
  obtainedAt: string | null; lastRenewalAttemptAt: string | null
  renewalFailureCount: number; lastError: string | null
  nodeId: string | null
}
```

### files

```ts
interface FileRootRef {
  id: string; kind: FileRootKind; label: string; writable: boolean; quotaBytes: number
}

interface FileEntryView {
  name: string; path: string; directory: boolean; sizeBytes: number
  mode: number; modeOctal: string; modifiedAt: string | null
  symlink: boolean; symlinkTarget: string | null; uid: number; gid: number
}

interface DirectoryPage {
  path: string; entries: FileEntryView[]; nextCursor: string | null; total: number
}

interface DirectorySizeView {
  path: string; bytes: number; fileCount: number; directoryCount: number
  approximate: boolean; quotaBytes: number
}

interface FileText {
  path: string; text: string; byteLength: number; truncated: boolean
  editable: boolean                 // derived from isEditable()
}

interface UploadProgress {
  sessionId: string; known: boolean
  totalBytes: number; receivedBytes: number
  received: {start: number; endExclusive: number}[]
  nextOffset: number; chunkSize: number
  expiresAt: string | null; complete: boolean
}

interface ChunkReceipt {
  sessionId: string; chunkIndex: number; receivedBytes: number; totalBytes: number
  nextOffset: number; nextChunkIndex: number; complete: boolean
}

interface TerminalView {sessionId: string; containerId: string; columns: number; rows: number}

// Decoded from the socket's binary frames, not from JSON. See §7, Terminal.
interface TerminalReady {columns: number; rows: number; containerId: string}
interface TerminalExit {code: number; reason: string}
```

### stats

```ts
interface MetricPoint {
  at: string
  cpuMillicores: number; cpuMillicoresMax: number
  memoryBytes: number; memoryBytesMax: number; memoryLimitBytes: number | null
  diskBytes: number
  networkRxBytes: number; networkTxBytes: number
  diskReadBytes: number; diskWriteBytes: number
  restartCount: number; sampleCount: number
}

interface MetricSeries {
  subjectId: string; source: MetricSource
  from: string; to: string; points: MetricPoint[]
  empty: boolean                    // derived
}

interface MetricWindow {from: string; to: string}

interface LogEvent {                        // SSE payload, not a page prop
  text: string; at: string; stderr: boolean; droppedBytes: number; end: boolean
}
```

### database

```ts
interface ManagedDatabaseView {
  id: string; organizationId: string; organizationName: string
  projectId: string; projectName: string
  name: string; username: string
  engine: EngineKind; engineLabel: string; engineVersion: string | null
  host: string; port: number; dedicated: boolean
  state: ManagedDatabaseState
  quotaBytes: number; usedBytes: number | null; measuredAt: string | null
  percentUsed: number; overQuota: boolean
  provisionedAt: string | null; passwordRotatedAt: string | null; lastError: string | null
  nodeId: string; nodeName: string; nodeReachable: boolean
  actionable: boolean; inFlight: boolean      // derived
}

interface DatabaseEngineView {
  id: string; engine: EngineKind; engineLabel: string; engineVersion: string | null
  image: string; mode: EngineMode
  organizationId: string | null; owner: string | null
  nodeId: string; nodeName: string; nodeReachable: boolean
  host: string; port: number; dataPath: string
  desiredState: EngineDesiredState; reportedState: string | null; reportedAt: string | null
  diskBytesUsed: number | null; lastError: string | null
  databaseCount: number; createdAt: string
  converged: boolean; removable: boolean      // derived
}

interface ConnectionString {                  // one-shot flash prop; carries the password
  databaseId: string; engine: EngineKind
  host: string; port: number; database: string
  username: string; password: string; charset: string | null
}
```

### backup

```ts
interface BackupView {
  id: string; name: string
  targetKind: BackupTargetKind; targetLabel: string
  destinationId: string; destinationName: string; destinationKind: DestinationKind
  schedule: string | null; timezone: string | null; enabled: boolean
  retentionCount: number; retentionDays: number
  lastRunAt: string | null; lastStatus: BackupRunStatus | null; lastError: string | null
  nextRunAt: string | null
  snapshotCount: number; latestSnapshotAt: string | null; storedBytes: number
  scheduled: boolean                // derived
}

interface DestinationView {
  id: string; name: string; kind: DestinationKind
  platformWide: boolean; editable: boolean; enabled: boolean
  endpoint: string | null; region: string | null; bucket: string | null
  pathPrefix: string | null; accessKeyId: string | null   // never the secret key
  storageClass: string | null; localPath: string | null
  lastCheckedAt: string | null; lastCheckError: string | null
  policyCount: number; snapshotCount: number; storedBytes: number
  provenReachable: boolean; removable: boolean    // derived
}

interface RestorePointView {
  id: string; targetKind: BackupTargetKind; targetLabel: string
  state: RestorePointState; trigger: RestorePointTrigger
  sizeBytes: number | null; encrypted: boolean; objectKey: string
  startedAt: string; finishedAt: string | null; expiresAt: string | null
  errorMessage: string | null
  destinationName: string; backupName: string | null
  lastVerifiedAt: string | null; activeRestores: number; restorable: boolean
  proven: boolean                   // derived
}

interface RestoreRunView {
  id: string; restorePointId: string; targetLabel: string
  snapshotTakenAt: string | null; mode: RestoreMode; state: RestoreState
  nodeId: string | null
  startedAt: string | null; finishedAt: string | null
  bytesRestored: number | null; errorMessage: string | null; log: string | null
  safetyRestorePointId: string | null; requestedBy: string | null
  finished: boolean                 // derived
}

interface BackupTargetOption {
  kind: BackupTargetKind; id: string; label: string
  eligible: boolean; policyCount: number
}
```

### node

```ts
interface NodeSummary {
  id: string; name: string; description: string | null
  lifecycle: NodeLifecycle
  suspensionReason: NodeSuspensionReason | null; suspensionExplanation: string | null
  schedulable: boolean; tags: string[]; publicAddress: string | null
  connectionState: NodeConnectionState; connected: boolean
  lastHeartbeatAt: string | null
  agentVersion: string | null; protocolVersion: number | null; needsUpgrade: boolean
  desiredGeneration: number; appliedGeneration: number; converged: boolean
  workloadCount: number; runningWorkloadCount: number
  cpuMillicoresCapacity: number | null; cpuMillicoresUsed: number | null
  memoryBytesCapacity: number | null; memoryBytesUsed: number | null
  diskBytesCapacity: number | null; diskBytesUsed: number | null
  lessIsolated: boolean             // no runsc: say so out loud
  quotaAdvisory: boolean            // not XFS: disk limits are not enforced
  dockerHealthy: boolean | null; reconcileError: string | null
}

interface NodeDetail {
  summary: NodeSummary
  doctor: NodeDoctorReport | null
  tokens: EnrolmentTokenView[]
  liveToken: EnrolmentTokenView | null
  installCommand: string; installChecksum: string; dialEndpoint: string
  upgradeAvailable: string | null   // the version, or null
  lastConnectedAt: string | null; lastDisconnected: string | null
  clockSkewMillis: number | null
  volumeFilesystem: string | null; kernelVersion: string | null
  osDescription: string | null; dockerVersion: string | null
}

interface NodeDoctorReport {
  takenAt: string | null; agentVersion: string | null; requiredChecksPassed: boolean
  checks: DoctorCheck[]; machine: DoctorMachine | null
}

interface DoctorCheck {
  id: string; title: string
  severity: DoctorSeverity; outcome: DoctorOutcome
  detail: string; remedy: string
}

interface DoctorMachine {
  hostname: string; operatingSystem: string; kernelVersion: string; architecture: string
  cpuCores: number; memoryBytes: number; diskTotalBytes: number; diskFreeBytes: number
  stateFilesystem: string; projectQuotaSupported: boolean; cgroupsV2: boolean
  dockerVersion: string; dockerApiVersion: string
  runscAvailable: boolean; runscVersion: string
  port80Free: boolean; port443Free: boolean
  clockSynchronised: boolean; clockOffsetMillis: number
  advertiseAddresses: string[]
}

interface EnrolmentTokenView {
  id: string; state: EnrolmentTokenState
  createdAt: string; expiresAt: string
  usedAt: string | null; usedFromAddress: string | null; revokedAt: string | null
}
```

`DoctorCheck` and `DoctorMachine` are `NodeDoctorReport.Check` and
`NodeDoctorReport.Machine` in Java - nested records, so the JSON is a plain object either
way. `severity` and `outcome` are stored as strings with the proto's
`DOCTOR_SEVERITY_` / `DOCTOR_OUTCOME_` prefix already removed.

### audit and jobs

```ts
interface AuditLogEntry {
  id: string; occurredAt: string; organizationId: string | null
  actorKind: AuditActorKind; actorAccountId: string | null; nodeId: string | null
  actorLabel: string; action: string
  targetKind: string | null; targetId: string | null; targetLabel: string | null
  outcome: AuditOutcome
  remoteAddress: string | null; requestId: string | null; detail: string | null
  refusal: boolean                  // derived from isRefusal()
}

interface AuditLogPage {
  entries: AuditLogEntry[]; total: number; offset: number; pageSize: number
}

interface JobQueueSummary {
  queued: number; due: number; running: number; failing: number
  healthy: boolean                  // derived
}

interface FailedJob {
  taskName: string; instanceId: string; executionTime: string
  consecutiveFailures: number
  lastFailure: string | null; lastSuccess: string | null
  picked: boolean; pickedBy: string | null; lastHeartbeat: string | null
  actionable: boolean               // derived
}

interface FailedJobPage {
  jobs: FailedJob[]; total: number; offset: number; pageSize: number
  empty: boolean                    // derived
}
```

---

## 6. The pages

`viewerRole` is `MemberRole` throughout and is what a page hides a button behind.
`OWNER`, `ADMIN` and `DEVELOPER` may write; `VIEWER` may not. The server checks anyway -
hiding a control is courtesy, not authorization.

### Auth - `features/auth/`

| Component | Route | Controller | Props |
|---|---|---|---|
| `auth/SignIn` | `GET /login` | `auth.SignInController` | `notice: SignInNotice \| null` |
| `auth/TwoFactorChallenge` | `GET /login/two-factor` | `auth.TwoFactorChallengeController` | `email: string`, `recoveryCodesRemaining: number` |
| `auth/Profile` | `GET /settings/profile` | `auth.ProfileController` | `profile: AccountProfile` |
| `auth/Security` | `GET /settings/security` | `auth.SecuritySettingsController` | `profile: AccountProfile`, `sessions: SessionView[]`, `recoveryCodesRemaining: number`, `recoveryCodesIssued: number`, `enrolmentPending: boolean`, `enrolment?: TwoFactorEnrolment`, `recoveryCodes?: string[]` |
| `auth/ApiTokens` | `GET /settings/tokens` | `auth.ApiTokenSettingsController` | `tokens: ApiTokenView[]`, `organizations: AccountOrganization[]`, `scopes: ApiScope[]` (the ones this account may ask for - `nodes:*` only for a platform operator), `mayIssueUnscoped: boolean`, `issuedToken?: string`, `issuedTokenName?: string` |
| `auth/AdminAccounts` | `GET /admin/accounts` | `auth.AdminAccountController` | `accounts: AccountProfile[]`, `activeAdminCount: number`, `roles: PlatformRole[]` |

`GET /settings` redirects to `/settings/profile`. `SignIn` and `TwoFactorChallenge` render
without app chrome: set no `layout` on them.

Writes: `POST /settings/profile`, `/settings/password`,
`/settings/security/two-factor/{begin,confirm,disable}`,
`/settings/security/recovery-codes`, `/settings/sessions/{id}/revoke`,
`/settings/sessions/revoke-all`, `/settings/tokens`, `/settings/tokens/{id}/revoke`,
`/admin/accounts`, `/admin/accounts/{id}/{suspend,reactivate}`.

### Organization - `features/org/`

| Component | Route | Controller | Props |
|---|---|---|---|
| `org/OrganizationList` | `GET /orgs` | `org.OrganizationController` | `memberships: OrganizationSummary[]`, `invitations: OrganizationSummary[]`, `plans: Plan[]` |
| `org/OrganizationOverview` | `GET /orgs/{organizationId}` | `org.OrganizationController` | `plan: Plan \| null`, `allowances: QuotaAllowance[]`, `memberCount: number`, `viewerRole: MemberRole` |
| `org/MemberList` | `GET /orgs/{organizationId}/members` | `org.MemberController` | `members: MemberView[]`, `viewer: Membership`, `roles: MemberRole[]`, `seats: QuotaAllowance` |
| `org/AdminOrganizationList` | `GET /admin/organizations` | `org.AdminOrganizationController` | `tenants: Organization[]`, `plans: Plan[]` |
| `org/AdminOrganizationDetail` | `GET /admin/organizations/{organizationId}` | `org.AdminOrganizationController` | `tenant: Organization`, `plan: Plan \| null`, `plans: Plan[]`, `allowances: QuotaAllowance[]`, `overrides: QuotaOverride[]`, `members: MemberView[]`, `resources: QuotaResource[]` |
| `org/AdminPlanList` | `GET /admin/plans` | `org.AdminPlanController` | `plans: Plan[]` |
| `org/AdminPlanDetail` | `GET /admin/plans/{planId}` | `org.AdminPlanController` | `plan: Plan`, `limits: PlanLimit[]`, `tenantCount: number` |

`OrganizationOverview` deliberately has no `organization` prop of its own: the shared prop
already holds the one in the URL.

### Projects and services - `features/project/`, `features/service/`

| Component | Route | Controller | Props |
|---|---|---|---|
| `project/Dashboard` | `GET /` | `project.DashboardController` | `projects: ProjectSummary[]` |
| `project/ProjectOverview` | `GET /projects/{projectId}` | `project.ProjectController` | `project: Project`, `services: ServiceSummary[]`, `viewerRole: MemberRole`, `serviceAllowance: QuotaAllowance` |
| `project/ProjectSettings` | `GET /projects/{projectId}/settings` | `project.ProjectSettingsController` | `project: Project`, `serviceCount: number`, `viewerRole: MemberRole` |
| `project/NewService` | `GET /projects/{projectId}/services/new` | `project.ProjectServicesController` | `project: Project`, `viewerRole: MemberRole`, `kinds: ServiceKind[]`, `presets: BuildPreset[]`, `isolations: RuntimeIsolation[]`, `restartPolicies: RestartPolicy[]`, `defaults: ServiceDraft`, `allowances: QuotaAllowance[]` (SERVICE, MEMORY_BYTES, CPU_MILLICORES, in that order) |
| `service/ServiceOverview` | `GET /services/{serviceId}` | `service.ServiceController` | `service: ServiceView`, `status: ServiceSummary \| null`, `project: Project`, `viewerRole: MemberRole`, `counts: {envVars: number; secrets: number; volumes: number; scheduledTasks: number}` |
| `service/ServiceSettings` | `GET /services/{serviceId}/settings` | `service.ServiceSettingsController` | `service: ServiceView`, `viewerRole: MemberRole`, `presets: BuildPreset[]`, `isolations: RuntimeIsolation[]`, `restartPolicies: RestartPolicy[]` |
| `service/ServiceEnvironment` | `GET /services/{serviceId}/environment` | `service.EnvironmentController` | `service: ServiceView`, `variables: EnvVar[]`, `secrets: SecretView[]`, `viewerRole: MemberRole` |
| `service/ServiceVolumes` | `GET /services/{serviceId}/volumes` | `service.VolumeController` | `service: ServiceView`, `volumes: Volume[]`, `allowance: QuotaAllowance` (VOLUME_BYTES), `viewerRole: MemberRole` |
| `service/ServiceTasks` | `GET /services/{serviceId}/tasks` | `service.ScheduledTaskController` | `service: ServiceView`, `tasks: CronTaskView[]`, `policies: ConcurrencyPolicy[]`, `allowance: QuotaAllowance` (CRON_TASK), `viewerRole: MemberRole` |

`status` on `ServiceOverview` is null before a node has reported, and that is a real
state: the page says "not placed yet" rather than drawing an empty pill. Intent
(`service.desiredState`) and fact (`status.reportedState`) are two props on purpose - a
service the customer asked to run and the node says has crashed is the interesting case,
and one prop could not show it.

Writes: `POST /projects`, `/projects/{id}`, `/projects/{id}/{archive,restore,delete}`,
`/projects/{id}/services`, `/services/{id}/settings`, `/services/{id}/delete`,
`/services/{id}/{start,stop,restart}`, `/services/{id}/environment/variables[/delete]`,
`/services/{id}/environment/secrets[/delete]`, `/services/{id}/volumes[/resize|/delete]`,
`/services/{id}/tasks[/update|/delete]`.

### Deployments - `features/deploy/`

| Component | Route | Controller | Props |
|---|---|---|---|
| `deploy/DeploymentList` | `GET /services/{serviceId}/deployments` | `deploy.DeploymentController` | `service: DeploymentTarget`, `deployments: DeploymentSummary[]`, `viewerRole: MemberRole`, `deploymentAllowance: QuotaAllowance` (DEPLOYMENTS_PER_DAY) |
| `deploy/DeploymentDetail` | `GET /services/{serviceId}/deployments/{deploymentId}` | `deploy.DeploymentController` | `service: DeploymentTarget`, `deployment: DeploymentSummary`, `log: DeploymentLog[]`, `logCursor: number`, `viewerRole: MemberRole` |

The log is rendered from the table so a finished build is readable the instant the page
paints. The page then opens the SSE feed with `?after=<logCursor>` and receives only what
it does not already have.

Writes: `POST /services/{id}/deployments` (field `ref`),
`/services/{id}/deployments/upload` (multipart archive),
`/services/{id}/deployments/{deploymentId}/{cancel,rollback}`.

### Domains - `features/domain/`

Not yet built. The contract:

| Component | Route | Controller | Props |
|---|---|---|---|
| `domain/ServiceDomains` | `GET /services/{serviceId}/domains` | `domain.DomainController` | `service: ServiceView`, `domains: DomainView[]`, `allowance: QuotaAllowance` (DOMAIN), `viewerRole: MemberRole`, `nodeAddress: string \| null` |

`nodeAddress` is `node.public_address` for the service's active placement, so the page can
tell the customer which A record to create. Null when nothing holds the service.

Writes: `POST /services/{id}/domains`, `/services/{id}/domains/{domainId}/verify`,
`/services/{id}/domains/{domainId}/remove`.

### Files and terminal - `features/files/`

| Component | Route | Controller | Props |
|---|---|---|---|
| `files/FileManager` | `GET /services/{serviceId}/files` | `files.FileManagerController` | `service: ServiceLocation`, `roots: FileRootRef[]`, `root: FileRootRef`, `path: string`, `parentPath: string`, `canWrite: boolean`, `showHidden: boolean`, `chunkSize: number`, `maxEditableBytes: number`, `page: DirectoryPage`, `unavailable: string \| null` |
| `files/Terminal` | `GET /services/{serviceId}/terminal` | `files.TerminalController` | `service: ServiceLocation`, `canOpen: boolean`, `placed: boolean`, `idleTimeoutSeconds: number`, `maxDurationSeconds: number` |

`unavailable` is the human sentence to show instead of a listing when the node is offline,
the service is not placed, or the operation was refused. `page` is then
`DirectoryPage.empty(path)` - an empty listing, never a missing prop. The page must render
the sentence, not an empty folder.

Query parameters on `FileManager`: `rootId`, `path`, `hidden`. All optional; `rootId`
defaults to the first root the customer may read.

### Logs and metrics - `features/stats/`

| Component | Route | Controller | Props |
|---|---|---|---|
| `stats/ServiceLogs` | `GET /services/{serviceId}/logs` | `stats.ServiceLogController` | `service: ServiceLocation`, `source: LogSourceName`, `subjectId: string`, `tailLines: number`, `placed: boolean` |
| `stats/ServiceMetrics` | `GET /services/{serviceId}/metrics` | `stats.ServiceMetricsController` | `service: ServiceLocation`, `series: MetricSeries`, `window: MetricWindow`, `liveWindowSeconds: number` |
| `stats/AdminNodeMetrics` | `GET /admin/metrics/nodes/{nodeId}` | `stats.AdminNodeMetricsController` | `nodeId: string`, `series: MetricSeries`, `window: MetricWindow`, `liveWindowSeconds: number` |

Query parameters: `source`, `subjectId`, `tail` on the log page; `window`, `from`, `to` on
both metrics pages.

`source` accepts `cron` or `LOG_SOURCE_CRON`, case-insensitively; anything else - including
nothing - is the container's own output, because that is what somebody who opened a log page
without saying meant. `subjectId` is honoured only for a cron run: for a container it is
taken from the path, so a member of one organization cannot read another's output by editing
a parameter.

### Databases - `features/database/`

| Component | Route | Controller | Props |
|---|---|---|---|
| `database/DatabaseList` | `GET /databases` | `database.DatabaseController` | `databases: ManagedDatabaseView[]`, `engines: EngineKind[]` |
| `database/DatabaseDetail` | `GET /databases/{databaseId}` | `database.DatabaseController` | `database: ManagedDatabaseView`, `viewerRole: MemberRole`, `databaseAllowance: QuotaAllowance` (MANAGED_DATABASE), `connection?: ConnectionString` |
| `database/AdminDatabaseEngineList` | `GET /admin/databases` | `database.AdminDatabaseEngineController` | `engines: DatabaseEngineView[]`, `overQuota: ManagedDatabaseView[]`, `kinds: EngineKind[]` |
| `database/AdminDatabaseEngineDetail` | `GET /admin/databases/{engineId}` | `database.AdminDatabaseEngineController` | `engine: DatabaseEngineView` |

`connection` carries the password in clear and arrives only after `POST
/databases/{id}/reveal` or `POST /databases/{id}/password`. Show it once, behind a copy
button, and do not put it in the browser's history or in any client-side store.

### Backups - `features/backup/`

| Component | Route | Controller | Props |
|---|---|---|---|
| `backup/BackupList` | `GET /backups/{organizationId}` | `backup.BackupController` | `policies: BackupView[]`, `destinations: DestinationView[]`, `targets: BackupTargetOption[]`, `restores: RestoreRunView[]`, `snapshotAllowance: QuotaAllowance \| null`, `byteAllowance: QuotaAllowance \| null`, `viewerRole: MemberRole \| null` |
| `backup/Destinations` | `GET /backups/{organizationId}/destinations` | `backup.BackupDestinationController` | `destinations: DestinationView[]`, `nodeBackupRoot: string`, `viewerRole: MemberRole` |
| `backup/Snapshots` | `GET /backups/{organizationId}/snapshots` | `backup.RestorePointController` | `snapshots: RestorePointView[]`, `restores: RestoreRunView[]`, `byteAllowance: QuotaAllowance`, `viewerRole: MemberRole` |
| `backup/RestoreRun` | `GET /backups/{organizationId}/restores/{restoreRunId}` | `backup.RestoreRunController` | `restore: RestoreRunView`, `viewerRole: MemberRole` |
| `backup/AdminDestinations` | `GET /admin/backups` | `backup.AdminBackupDestinationController` | `destinations: DestinationView[]`, `nodeBackupRoot: string` |

`GET /backups` redirects to the first accepted organization. An account with none renders
`backup/BackupList` with **every list empty and every allowance and `viewerRole` null** -
the empty state, not a redirect loop. The page must handle that shape.

### Nodes - `features/node/`

| Component | Route | Controller | Props |
|---|---|---|---|
| `node/AdminNodeList` | `GET /admin/nodes` | `node.AdminNodeController` | `nodes: NodeSummary[]`, `attention: NodeSummary[]` |
| `node/AdminNodeDetail` | `GET /admin/nodes/{nodeId}` | `node.AdminNodeController` | `node: NodeDetail`, `bootstrapToken?: string`, `bootstrapTokenExpiresAt?: string`, `installCommand?: string`, `installChecksum?: string` |

There is no separate enrol wizard, doctor page, drain page or upgrade page. All four are
sections of `AdminNodeDetail`, because all four are things you do to a node while looking
at it:

- **Enrol** - `POST /admin/nodes/{id}/tokens` issues a bootstrap token and redirects back
  with the four flash props above. The page shows the one-line install command, the
  checksum to compare by eye, and a countdown to `liveToken.expiresAt`. The token is
  single-use with a fifteen-minute TTL and is never shown again.
- **Doctor** - `node.doctor` is the report the node sent at enrolment and re-sent on every
  reconnect. Render `checks` grouped by `outcome`, `FAIL` first, each with its `remedy`.
  `requiredChecksPassed === false` is the banner. `machine.runscAvailable === false` and
  `machine.projectQuotaSupported === false` are the two that must be visible without
  expanding anything: they mean less isolation and unenforceable disk quotas, and a
  platform that hides those is claiming guarantees it does not have.
- **Drain** - `POST /admin/nodes/{id}/drain-survey` reports what *would* happen and changes
  nothing; `POST /admin/nodes/{id}/drain` does it. Both redirect back with a flash. A
  workload with a volume is listed and never moved automatically.
- **Upgrade** - `node.upgradeAvailable` is the version the panel would install, or null.
  `POST /admin/nodes/{id}/upgrade`. A failed upgrade rolls back to the previous binary and
  the node reports that; the page shows it plainly rather than leaving the version
  looking stuck.

Also: `POST /admin/nodes`, `/admin/nodes/{id}/settings`,
`/admin/nodes/{id}/tokens/{tokenId}/revoke`, `/admin/nodes/{id}/{suspend,resume}`,
`/admin/nodes/{id}/delete` (requires the node name typed as confirmation).

### Audit and jobs - `features/audit/`, `features/jobs/`

| Component | Route | Controller | Props |
|---|---|---|---|
| `audit/AdminAudit` | `GET /admin/audit` | `audit.AdminAuditController` | `page: AuditLogPage`, `filter: AuditFilter`, `actions: Record<string, string[]>`, `targetKinds: string[]`, `outcomes: AuditOutcome[]` |
| `jobs/AdminJobs` | `GET /admin/jobs` | `jobs.AdminJobController` | `summary: JobQueueSummary`, `page: FailedJobPage` |

```ts
interface AuditFilter {             // the sanitised query, echoed back for the form
  organizationId: string | null; accountId: string | null; nodeId: string | null
  action: string | null; targetKind: string | null; targetId: string | null
  outcome: AuditOutcome | null
  from: string | null; to: string | null
  limit: number; offset: number
}
```

`actions` is the vocabulary grouped by the word before the dot, for the filter's option
groups. It is derived from the list in [`panel-ports.md`](panel-ports.md) §2.4 and is the
authoritative set the page offers - never a hand-written copy.

The filter is echoed from the *sanitised* query, not from the raw parameters, so a value
the search dropped does not stay in the box claiming to be in effect.

Writes: `POST /admin/jobs/retry`, `/admin/jobs/discard`, both with `taskName` and
`instanceId`.

### Error - `features/error/`

| Component | Route | Controller | Props |
|---|---|---|---|
| `error/Error` | any failure, via `/error` | `web.ErrorPageController` | `status: number`, `reason: string`, `message: string`, `path: string` |

Already implemented. It renders app chrome only when `account` is non-null, and it is a
real page: a 404 says what was not found, a 500 says the failure was recorded.

---

## 7. Endpoints a page calls that are not pages

These answer JSON or SSE, not an Inertia page. They are listed because a page's props are
only half of what a feature needs.

### Files

| Method | Path | Answers |
|---|---|---|
| `GET` | `/services/{id}/files/roots` | `FileRootRef[]` |
| `GET` | `/services/{id}/files/list?rootId&path&cursor&pageSize&hidden` | `DirectoryPage` |
| `GET` | `/services/{id}/files/stat?rootId&path` | `FileEntryView` |
| `GET` | `/services/{id}/files/size?rootId&path` | `DirectorySizeView` |
| `GET` | `/services/{id}/files/content?rootId&path` | `FileText` |
| `GET` | `/services/{id}/files/download?rootId&path` | the bytes, `Content-Disposition: attachment` |
| `POST` | `/services/{id}/files/content` | redirect; the editor's save |
| `POST` | `/services/{id}/files/{folder,rename,delete,chmod,archive,extract}` | redirect + flash |
| `POST` | `/services/{id}/files/uploads` | `UploadProgress` - opens a resumable session |
| `GET` | `/services/{id}/files/uploads/{sessionId}` | `UploadProgress` - what the node already has |
| `POST` | `/services/{id}/files/uploads/{sessionId}/chunks` | `ChunkReceipt` |
| `POST` | `/services/{id}/files/uploads/{sessionId}/complete` | `FileEntryView` |
| `POST` | `/services/{id}/files/uploads/{sessionId}/abort` | `UploadProgress` |

The upload is chunked and resumable because most customers are on a phone. After a dropped
connection the client re-reads `UploadProgress`, which reports `received` as byte ranges
and `nextOffset` as where to carry on from. `chunkSize` comes from the page's props, not
from a constant in the client.

### Terminal

The one WebSocket in the panel. Everything else realtime is SSE and stays SSE; a terminal
is the only stream that is not one-way, and carrying its input over HTTP meant a request
per keystroke - each through the whole security filter chain, each re-reading the
signed-in account. A socket is authorised once, at the handshake.

| Method | Path | Answers |
|---|---|---|
| `POST` | `/services/{id}/terminal` | `TerminalView` - mints the session id |
| `GET` | `/services/{id}/terminal/socket?session={sessionId}` | `101`, or `403`/`404`/`400` |

Opening stays a `POST`: it starts a process inside a customer's container and writes the
`terminal.open` audit entry, so it belongs behind the CSRF token, which a handshake - a
`GET` - cannot carry. The socket attaches to the session that post minted and never opens
one.

The handshake is where every check happens, and a refusal is an HTTP status on the upgrade
rather than an accepted socket that closes: `404` when the service is not the caller's or
the session is not theirs, `403` for a `VIEWER`, `400` for a request that names no session.
Same-origin only, which is what stands in for CSRF here.

Frames are binary in both directions, one tag byte and then the payload. Three tags, the
same three as `terminal.proto`:

| Tag | Browser sends | Panel sends |
|---|---|---|
| `0x00` bytes | keystrokes | PTY output |
| `0x01` size | `u16` cols, `u16` rows | `u16` cols, `u16` rows, UTF-8 container id |
| `0x02` end | *(nothing)* - end my shell | `i32` exit code, UTF-8 reason |

Binary, so there is no base64 and no place left to treat a PTY's bytes as a string - which
is the bug that broke the predecessor (design §11.5). The size frame is sent by the panel
as soon as a socket attaches, including after a reconnect, because xterm has to be told the
size the PTY settled on after clamping.

A frame carries at most 64 kB of input; a longer paste is split by the client and refused
by the panel. The panel pings on `wisper.files.terminal-keep-alive`, because a tunnel closes
a connection that has been quiet.

Closing the socket ends the shell. That makes a closed tab free - the browser drops the
connection and the panel releases the session - so there is no unload handler to forget. A
client that reconnects with the same session id within a second or two may still find the
shell, because the panel has not always noticed the old connection died; that is what makes
a phone moving from wi-fi to mobile data survive.

### Logs and metrics

| Method | Path | SSE events |
|---|---|---|
| `GET` | `/services/{id}/deployments/{deploymentId}/log?after=<sequence>` | `line` (`DeploymentLog`), `end` (the last sequence, as a bare number) |
| `GET` | `/services/{id}/logs/stream?source&subjectId&tail&since` | `log` (`LogEvent`), `end` (`LogEvent` with `end: true`) |
| `GET` | `/services/{id}/metrics/series?from&to` | JSON `MetricSeries` (not SSE) |
| `GET` | `/services/{id}/metrics/live` | `series` (`MetricSeries`, the backfill), then `point` (`MetricPoint`) |
| `GET` | `/admin/metrics/nodes/{nodeId}/series?from&to` | JSON `MetricSeries` |
| `GET` | `/admin/metrics/nodes/{nodeId}/live` | `series`, then `point` |

Every stream sends `: alive` comments on an interval; a tunnel closes an idle connection.
`LogEvent.droppedBytes > 0` must be shown to the customer - a gap they are told about can
be acted on, a silent one cannot.

Close the `EventSource` on unmount. The server registers its cleanup on the emitter's
completion, timeout **and** error callbacks, because all three fire for a customer who
closed a tab and only one of them looks like success.

---

## 8. Rules for the pages themselves

1. **Mobile-first.** Most customers arrive on a phone. A list is one column with swipe
   actions, not a four-column table that scrolls sideways. The terminal and the file
   editor carry an extra key bar (Tab, arrows, `{}`, `/`), and the shell writes
   `viewport-fit=cover` so that bar clears the home indicator.
2. **Every page in the navigation renders something.** The predecessor shipped seven empty
   screens; that is the reason this project exists. A page with no data renders its empty
   state, and the empty state says what to do next.
3. **Never re-implement a permission.** `viewerRole` decides what to *show*. The server
   decides what to *allow*, and it decides again on every request.
4. **Write with `router.post`.** Inertia is configured `forceFormData: true`; every body
   arrives as multipart. Answer is always a redirect, and `errors` / `flash` come back as
   shared props.
5. **A page component is `<Name>Page.tsx` in `features/<feature>/`.** Helpers live beside
   it in the same folder, not in a `components/` bucket at the root of `src/`.
