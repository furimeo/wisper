/**
 * The `node` package's records, as they arrive on a page.
 *
 * Every interface here is one Java record in `lhqm.furimeo.wisper.node`, with its
 * components in declaration order and its derived accessors marked.
 * `docs/contracts/pages.md` §5 is the source; a field that is not there is not sent, and
 * adding one here does not make it appear.
 */

/** `node.NodeLifecycle`. Where the record is in a machine's life. */
export type NodeLifecycle =
  | 'CREATED'
  | 'ENROLLED'
  | 'DRAINING'
  | 'DRAINED'
  | 'SUSPENDED'
  | 'RETIRED'

/** `node.NodeConnectionState`. Whether a control stream is open right now. */
export type NodeConnectionState = 'DISCONNECTED' | 'CONNECTED' | 'DEGRADED'

/** `node.NodeSuspensionReason`. Why the panel stopped publishing to a machine. */
export type NodeSuspensionReason =
  | 'DUPLICATE_FINGERPRINT'
  | 'PROTOCOL_MISMATCH'
  | 'OPERATOR'
  | 'CREDENTIAL_REVOKED'

/** `node.EnrolmentTokenView.State`. */
export type EnrolmentTokenState = 'LIVE' | 'USED' | 'REVOKED' | 'EXPIRED'

/**
 * The proto enum with its `DOCTOR_SEVERITY_` prefix already stripped by
 * `NodeDoctorReport.Check`.
 */
export type DoctorSeverity = 'REQUIRED' | 'ADVISORY' | 'UNSPECIFIED'

/** Likewise, `DOCTOR_OUTCOME_` stripped. */
export type DoctorOutcome = 'PASS' | 'WARN' | 'FAIL' | 'UNSPECIFIED'

/**
 * `node.NodeSummary`. One row of the fleet, and the top of one node's page.
 *
 * `lessIsolated` and `quotaAdvisory` are not decoration. The first means every container
 * on the machine runs under `runc` rather than gVisor; the second means a volume's size
 * limit is a number the panel displays and nothing enforces. Both are guarantees the
 * platform is not keeping, and a screen that renders them as "fine" is lying.
 */
export interface NodeSummary {
  id: string
  name: string
  description: string | null
  lifecycle: NodeLifecycle
  suspensionReason: NodeSuspensionReason | null
  suspensionExplanation: string | null
  schedulable: boolean
  tags: string[]
  publicAddress: string | null
  connectionState: NodeConnectionState
  connected: boolean
  lastHeartbeatAt: string | null
  agentVersion: string | null
  protocolVersion: number | null
  needsUpgrade: boolean
  desiredGeneration: number
  appliedGeneration: number
  converged: boolean
  workloadCount: number
  runningWorkloadCount: number
  cpuMillicoresCapacity: number | null
  cpuMillicoresUsed: number | null
  memoryBytesCapacity: number | null
  memoryBytesUsed: number | null
  diskBytesCapacity: number | null
  diskBytesUsed: number | null
  /** No `runsc` on the machine: weaker isolation, and it is said out loud. */
  lessIsolated: boolean
  /** Not XFS: the disk quota on every volume here is advisory. */
  quotaAdvisory: boolean
  dockerHealthy: boolean | null
  reconcileError: string | null
}

/** `node.NodeDoctorReport.Check`. One preflight check and what to do about it. */
export interface DoctorCheck {
  id: string
  title: string
  severity: DoctorSeverity
  outcome: DoctorOutcome
  detail: string
  remedy: string
}

/** `node.NodeDoctorReport.Machine`. What the machine is, as it last reported itself. */
export interface DoctorMachine {
  hostname: string
  operatingSystem: string
  kernelVersion: string
  architecture: string
  cpuCores: number
  memoryBytes: number
  diskTotalBytes: number
  diskFreeBytes: number
  stateFilesystem: string
  projectQuotaSupported: boolean
  cgroupsV2: boolean
  dockerVersion: string
  dockerApiVersion: string
  runscAvailable: boolean
  runscVersion: string
  port80Free: boolean
  port443Free: boolean
  clockSynchronised: boolean
  clockOffsetMillis: number
  advertiseAddresses: string[]
}

/** `node.NodeDoctorReport`. The last `sasayaki doctor` run this machine sent. */
export interface NodeDoctorReport {
  takenAt: string | null
  agentVersion: string | null
  requiredChecksPassed: boolean
  checks: DoctorCheck[]
  machine: DoctorMachine | null
}

/** `node.EnrolmentTokenView`. One bootstrap token, never its text. */
export interface EnrolmentTokenView {
  id: string
  state: EnrolmentTokenState
  createdAt: string
  expiresAt: string
  usedAt: string | null
  usedFromAddress: string | null
  revokedAt: string | null
}

/** `node.NodeDetail`. Everything one machine's page shows. */
export interface NodeDetail {
  summary: NodeSummary
  doctor: NodeDoctorReport | null
  tokens: EnrolmentTokenView[]
  liveToken: EnrolmentTokenView | null
  /** Present only while a token is live: the command is useless without one. */
  installCommand: string | null
  installChecksum: string
  dialEndpoint: string
  /** The newer version the panel would install, or null when there is none. */
  upgradeAvailable: string | null
  lastConnectedAt: string | null
  lastDisconnected: string | null
  clockSkewMillis: number | null
  volumeFilesystem: string | null
  kernelVersion: string | null
  osDescription: string | null
  dockerVersion: string | null
}
