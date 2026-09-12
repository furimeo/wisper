import {t} from '@/i18n'
import type {BadgeTone} from '@/shell'

import type {
  DeploymentSource,
  DeploymentStatus,
  DeploymentSummary,
  DeploymentTarget,
  DeploymentTrigger,
} from './deployTypes'

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
  return t(`deploy.status.${status}`)
}

export function statusSentence(status: DeploymentStatus): string {
  return t(`deploy.status_sentence.${status}`)
}

export function statusTone(status: DeploymentStatus): BadgeTone {
  return STATUS_TONES[status] ?? 'neutral'
}

export function triggerLabel(trigger: DeploymentTrigger): string {
  return t(`deploy.trigger.${trigger}`)
}

export function sourceLabel(source: DeploymentSource): string {
  return t(`deploy.source.${source}`)
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
    return '-'
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
    parts.push(t('deploy.describe.rolled_back_to', {sequence: deployment.rolledBackFromSequence}))
  }
  parts.push(deployment.triggeredBy ?? triggerLabel(deployment.trigger))
  return parts.join(' · ')
}

/** The two providers `GitWebhookController` maps. */
export type WebhookProvider = 'github' | 'gitlab'

/**
 * The absolute URL to paste into a Git provider.
 */
export function webhookUrl(target: DeploymentTarget, provider: WebhookProvider): string {
  return window.location.origin + target.webhookPath.replace('{provider}', provider)
}
