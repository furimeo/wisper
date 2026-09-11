/**
 * The `database` package's records, as they arrive on a page.
 *
 * Every interface here is one Java record in `lhqm.furimeo.wisper.database`, with its
 * components in declaration order and its derived accessors marked.
 * `docs/contracts/pages.md` §5 is the source.
 */

/** `database.EngineKind`. The two engines the platform runs. */
export type EngineKind = 'POSTGRES' | 'MYSQL'

/** `database.EngineMode`. One container for everybody, or one for a single tenant. */
export type EngineMode = 'SHARED' | 'DEDICATED'

/** `database.EngineDesiredState`. What the operator asked the node for. */
export type EngineDesiredState = 'RUNNING' | 'STOPPED'

/** `database.ManagedDatabaseState`. */
export type ManagedDatabaseState = 'PENDING' | 'READY' | 'SUSPENDED' | 'FAILED' | 'DELETING'

/**
 * `database.ManagedDatabaseView`. One customer database, with no way to connect to it.
 *
 * There is deliberately no password and no URI here. Those live in {@link ConnectionString},
 * which the server builds only after somebody presses "show connection details" and only
 * after that press has been written to the audit trail.
 */
export interface ManagedDatabaseView {
  id: string
  organizationId: string
  organizationName: string
  projectId: string
  projectName: string
  name: string
  username: string
  engine: EngineKind
  engineLabel: string
  engineVersion: string | null
  host: string
  port: number
  dedicated: boolean
  state: ManagedDatabaseState
  quotaBytes: number
  /** Null until the node has measured it once, which is not the same as zero. */
  usedBytes: number | null
  measuredAt: string | null
  /** -1 when nothing has been measured. */
  percentUsed: number
  overQuota: boolean
  provisionedAt: string | null
  passwordRotatedAt: string | null
  lastError: string | null
  nodeId: string
  nodeName: string
  /** Whether the panel currently has a control stream to the node holding it. */
  nodeReachable: boolean
  /** Derived from `isActionable()`: usable, and the node can be reached. */
  actionable: boolean
  /** Derived from `isInFlight()`: the platform still owes this row work. */
  inFlight: boolean
}

/** `database.DatabaseEngineView`. One engine container, admin-facing. */
export interface DatabaseEngineView {
  id: string
  engine: EngineKind
  engineLabel: string
  engineVersion: string | null
  image: string
  mode: EngineMode
  organizationId: string | null
  owner: string | null
  nodeId: string
  nodeName: string
  nodeReachable: boolean
  host: string
  port: number
  dataPath: string
  desiredState: EngineDesiredState
  /** What the node said, or null before it has said anything. Not the same as "down". */
  reportedState: string | null
  reportedAt: string | null
  diskBytesUsed: number | null
  lastError: string | null
  databaseCount: number
  createdAt: string
  /** Derived from `isConverged()`: intent and report agree. */
  converged: boolean
  /** Derived from `isRemovable()`: it holds no customer databases. */
  removable: boolean
}

/**
 * `database.ConnectionString`. A one-shot flash prop that carries the password in clear.
 *
 * The URI forms are not sent: `uri()`, `redactedUri()` and `jdbcUrl()` are not bean-named
 * accessors, so Jackson leaves them behind. They are assembled on this side instead - see
 * `connectionUri` in `databaseVocabulary.ts`.
 */
export interface ConnectionString {
  databaseId: string
  engine: EngineKind
  host: string
  port: number
  database: string
  username: string
  password: string
  charset: string | null
}
