import type {BadgeTone} from '@/shell'

import type {
  DeploymentSource,
  DeploymentStatus,
  DeploymentSummary,
  DeploymentTarget,
  DeploymentTrigger,
} from './deployTypes'

/**
 * What each `deploy` enum is called on screen, and the three predicates the Java records
 * compute but do not send.
 *
 * The words match the Java `label()` methods deliberately: a flash message written by
 * `DeploymentController` and a pill written here land in the same sentence often enough
 * that two names for one state is a support ticket.
 */

const STATUS_LABELS: Record<DeploymentStatus, string> = {
  QUEUED: 'Queued',
  ASSIGNED: 'Assigned',
  BUILDING: 'Building',
  PUBLISHING: 'Publishing',
  SUCCEEDED: 'Succeeded',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
  SUPERSEDED: 'Superseded',
}

/**
 * One sentence per state, saying what is happening rather than restating the word.
 *
 * `SUPERSEDED` is the one nobody guesses: a queued build that a newer push overtook was
 * never run, and a customer who is not told that reads it as a failure of their commit.
 */
const STATUS_SENTENCES: Record<DeploymentStatus, string> = {
  QUEUED: 'Accepted and written down. A worker has not picked it up yet.',
  ASSIGNED: 'A node has been chosen and the work has been handed to it.',
  BUILDING: 'The node is cloning, installing and compiling.',
  PUBLISHING: 'The release is going in front of visitors. This cannot be cancelled.',
  SUCCEEDED: 'It is out.',
  FAILED: 'It stopped, and said why.',
  CANCELLED: 'Somebody stopped it before it finished.',
  SUPERSEDED: 'A newer deployment overtook it while it was still waiting, so it never ran.',
}

const STATUS_TONES: Record<DeploymentStatus, BadgeTone> = {
  QUEUED: 'neutral',
  ASSIGNED: 'accent',
  BUILDING: 'accent',
  PUBLISHING: 'accent',
  SUCCEEDED: 'running',
  FAILED: 'failed',
  CANCELLED: 'neutral',
  SUPERSEDED: 'neutral',
}

const TRIGGER_LABELS: Record<DeploymentTrigger, string> = {
  MANUAL: 'Deployed by hand',
  GIT_PUSH: 'Git push',
  ROLLBACK: 'Rollback',
  API: 'API token',
  SCHEDULED: 'Scheduled',
}

const SOURCE_LABELS: Record<DeploymentSource, string> = {
  GIT: 'From the repository',
  ARCHIVE: 'From an uploaded zip',
  IMAGE: 'From the configured image',
}

/** The statuses the platform still owes work on. Mirrors `DeploymentStatus.isTerminal`. */
const TERMINAL: readonly DeploymentStatus[] = [
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'SUPERSEDED',
]

/**
 * The statuses a customer may still stop.
 *
 * Mirrors `DeploymentStatus.isCancellable()`, which is `canTransitionTo(CANCELLED)`.
 * `PUBLISHING` is deliberately absent: once the symlink swap is under way, telling
 * somebody it was cancelled would be a claim about the world the panel cannot make good.
 */
const CANCELLABLE: readonly DeploymentStatus[] = ['QUEUED', 'ASSIGNED', 'BUILDING']

export function statusLabel(status: DeploymentStatus): string {
  return STATUS_LABELS[status] ?? status
}

export function statusSentence(status: DeploymentStatus): string {
  return STATUS_SENTENCES[status] ?? ''
}

export function statusTone(status: DeploymentStatus): BadgeTone {
  return STATUS_TONES[status] ?? 'neutral'
}

export function triggerLabel(trigger: DeploymentTrigger): string {
  return TRIGGER_LABELS[trigger] ?? trigger
}

export function sourceLabel(source: DeploymentSource): string {
  return SOURCE_LABELS[source] ?? source
}

/** Mirrors `DeploymentSummary.inFlight()`, which the browser is not sent. */
export function inFlight(deployment: DeploymentSummary): boolean {
  return !TERMINAL.includes(deployment.status)
}

/** Mirrors `DeploymentSummary.cancellable()`. */
export function cancellable(deployment: DeploymentSummary): boolean {
  return CANCELLABLE.includes(deployment.status)
}

/** Mirrors `DeploymentSummary.rollbackTarget()`: it succeeded and something else is live. */
export function rollbackTarget(deployment: DeploymentSummary): boolean {
  return deployment.status === 'SUCCEEDED' && !deployment.current
}

/** Mirrors `DeploymentSummary.shortCommit()`: the seven characters people recognise. */
export function shortCommit(sha: string | null): string | null {
  if (sha === null) {
    return null
  }
  return sha.length < 7 ? sha : sha.slice(0, 7)
}

/**
 * How long a build took, or has been taking.
 *
 * `durationMs` is only written when a deployment finishes, so a running build is measured
 * from `startedAt` against the caller's clock - which is why `now` is a parameter and not
 * read here: a component that ticks passes its own, and a list that does not passes none.
 */
export function durationOf(deployment: DeploymentSummary, now?: number): number | null {
  if (deployment.durationMs !== null) {
    return deployment.durationMs
  }
  if (deployment.startedAt === null || now === undefined) {
    return null
  }
  const started = Date.parse(deployment.startedAt)
  return Number.isFinite(started) ? Math.max(0, now - started) : null
}

/** "4.2s", "1m 12s", "1h 03m". Never "0ms": a build that fast did not happen. */
export function formatDuration(millis: number | null): string {
  if (millis === null || !Number.isFinite(millis)) {
    return '—'
  }
  const seconds = Math.max(0, Math.round(millis / 1000))
  if (seconds < 60) {
    return millis < 10_000 ? `${(millis / 1000).toFixed(1)}s` : `${seconds}s`
  }
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) {
    return `${minutes}m ${String(seconds % 60).padStart(2, '0')}s`
  }
  return `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, '0')}m`
}

/**
 * The line under a row: what was deployed, and who asked for it.
 *
 * The commit subject when there is one, because that is what a person recognises a
 * deployment by; otherwise the source, which is the only thing left to say about an app
 * redeploying its image or a site unpacked from a zip.
 */
export function describe(deployment: DeploymentSummary): string {
  const parts: string[] = []
  const commit = shortCommit(deployment.commitSha)
  if (deployment.commitSubject) {
    parts.push(deployment.commitSubject)
  } else if (commit) {
    parts.push(commit)
  } else {
    parts.push(sourceLabel(deployment.source))
  }
  if (deployment.rolledBackFromSequence !== null) {
    parts.push(`rolled back to #${deployment.rolledBackFromSequence}`)
  }
  parts.push(deployment.triggeredBy ?? triggerLabel(deployment.trigger))
  return parts.join(' · ')
}

/** The two providers `GitWebhookController` maps. */
export type WebhookProvider = 'github' | 'gitlab'

/**
 * The absolute URL to paste into a Git provider.
 *
 * `location.origin` rather than a configured base: the panel sits behind a tunnel and the
 * address the customer reached it on is the only one it can be sure of. Called from a
 * component, so `window` is always there.
 */
export function webhookUrl(target: DeploymentTarget, provider: WebhookProvider): string {
  return window.location.origin + target.webhookPath.replace('{provider}', provider)
}
