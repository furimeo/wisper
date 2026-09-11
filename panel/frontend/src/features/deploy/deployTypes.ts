import type {ServiceKind} from '@/features/service/serviceTypes'

/**
 * The `deploy` package's records and vocabularies, as they arrive on a page.
 *
 * Every interface here is one Java record in `lhqm.furimeo.wisper.deploy`, components in
 * declaration order. `docs/contracts/pages.md` §5 is the source: a field that is not there
 * is not sent, and adding one here does not make it appear.
 *
 * Note what is *absent*. `DeploymentSummary` has `shortCommit()`, `inFlight()`,
 * `cancellable()` and `rollbackTarget()` on the Java side and none of them reaches the
 * browser: Jackson only lifts a zero-argument accessor onto a record's JSON when it is
 * bean-named (`isXxx()`), and none of those four is. `deployVocabulary.ts` recomputes them
 * from `status` and `current`, which is the same rule written twice on purpose - the Java
 * copy guards the write, this copy decides what is drawn.
 */

/** `deploy.DeploymentStatus`. The state machine `DeploymentStatus.canTransitionTo` fixes. */
export type DeploymentStatus =
  | 'QUEUED'
  | 'ASSIGNED'
  | 'BUILDING'
  | 'PUBLISHING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'SUPERSEDED'

/** `deploy.DeploymentTrigger`. Who or what asked for this deployment. */
export type DeploymentTrigger = 'MANUAL' | 'GIT_PUSH' | 'ROLLBACK' | 'API' | 'SCHEDULED'

/** `deploy.DeploymentSource`. Where the bytes being deployed came from. */
export type DeploymentSource = 'GIT' | 'ARCHIVE' | 'IMAGE'

/** `deploy.DeploymentLogStream`. `SYSTEM` is the panel talking, not the build. */
export type DeploymentLogStream = 'STDOUT' | 'STDERR' | 'SYSTEM'

/**
 * `deploy.DeploymentTarget`. The service, cut down to what the deployments screen may see.
 *
 * The `Service` aggregate never reaches a browser - it carries the webhook secret and the
 * repository credential - so this is what both deployment pages get, and its key for the
 * service is `serviceId` rather than `id`.
 */
export interface DeploymentTarget {
  serviceId: string
  projectId: string
  name: string
  slug: string
  kind: ServiceKind
  repositoryUrl: string | null
  repositoryBranch: string | null
  autoDeploy: boolean
  /** How many releases the node keeps under `releases/`; older rows are pruned with them. */
  keepReleases: number
  /**
   * `/webhooks/{provider}/{serviceId}`, relative and with the placeholder still in it.
   *
   * Relative because the panel is behind a tunnel and does not reliably know its own
   * public name. `webhookUrl()` in `deployVocabulary.ts` substitutes the provider and
   * prefixes `location.origin`, which is by definition the address the customer reached.
   */
  webhookPath: string
  /** Whether pressing deploy has a repository to clone. A site with a repository URL. */
  deploysFromGit: boolean
  /** Whether the zip form is offered. Sites only: an app deploys the image it is given. */
  acceptsArchive: boolean
}

/**
 * `deploy.DeploymentSummary`. One row of the history, and the whole of the detail page.
 *
 * `current` is the live release, kept to one row per service by the
 * `deployment_current_idx` partial unique index rather than by a status - which is why
 * "live" is `status === 'SUCCEEDED' && current` and not a ninth value of the enum.
 */
export interface DeploymentSummary {
  id: string
  /** Per-service, from 1. What a person calls this deployment: "#41". */
  sequence: number
  status: DeploymentStatus
  trigger: DeploymentTrigger
  source: DeploymentSource
  gitRef: string | null
  commitSha: string | null
  /** The first line of the commit message only. A row is one line. */
  commitSubject: string | null
  commitAuthor: string | null
  current: boolean
  nodeId: string | null
  releasePath: string | null
  queuedAt: string
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  errorMessage: string | null
  /** The account's email, or null for a webhook - which has no person behind it. */
  triggeredBy: string | null
  /** The release this one restored, for the sentence "#14 rolled back to #11". */
  rolledBackFromSequence: number | null
}

/**
 * `deploy.DeploymentLog`, the aggregate, passed to the page as-is.
 *
 * One row per line rather than one growing column, because a reconnecting browser resumes
 * with "everything after line N" and a single column cannot answer that. `sequence` is
 * both the order and the SSE resume cursor.
 */
export interface DeploymentLog {
  id: string
  deploymentId: string
  sequence: number
  stream: DeploymentLogStream
  message: string
  loggedAt: string
  version: number
  /** Derived from `isFromPanel()`: the platform wrote this line, not the build. */
  fromPanel: boolean
}
