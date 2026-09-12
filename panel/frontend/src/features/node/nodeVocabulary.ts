import type {BadgeTone} from '@/shell'
import {t} from '@/i18n'

import type {
  DoctorOutcome,
  DoctorSeverity,
  EnrolmentTokenState,
  NodeConnectionState,
  NodeLifecycle,
  NodeSummary,
} from './nodeTypes'

/**
 * What each node enum is called on screen, and what colour a state is.
 *
 * The Java enums carry `explanation()` and `acceptsPlacements()` methods that Jackson does
 * not serialise - they are not bean-named - so the words live here, once, rather than in a
 * `switch` inside each of the six components that show a lifecycle.
 *
 * The sentences are written for somebody holding a phone at two in the morning. "DRAINED"
 * is not a sentence; "everything that could move has moved" is.
 */

const LIFECYCLE_TONES: Record<NodeLifecycle, BadgeTone> = {
  CREATED: 'neutral',
  ENROLLED: 'running',
  DRAINING: 'degraded',
  DRAINED: 'neutral',
  SUSPENDED: 'failed',
  RETIRED: 'neutral',
}

const CONNECTION_TONES: Record<NodeConnectionState, BadgeTone> = {
  CONNECTED: 'running',
  DEGRADED: 'degraded',
  DISCONNECTED: 'neutral',
}

const TOKEN_TONES: Record<EnrolmentTokenState, BadgeTone> = {
  LIVE: 'accent',
  USED: 'running',
  REVOKED: 'neutral',
  EXPIRED: 'neutral',
}

const OUTCOME_TONES: Record<DoctorOutcome, BadgeTone> = {
  PASS: 'running',
  WARN: 'degraded',
  FAIL: 'failed',
  UNSPECIFIED: 'neutral',
}

export function lifecycleLabel(lifecycle: NodeLifecycle): string {
  switch (lifecycle) {
    case 'CREATED':
      return t('node.lifecycle.created')
    case 'ENROLLED':
      return t('node.lifecycle.enrolled')
    case 'DRAINING':
      return t('node.lifecycle.draining')
    case 'DRAINED':
      return t('node.lifecycle.drained')
    case 'SUSPENDED':
      return t('node.lifecycle.suspended')
    case 'RETIRED':
      return t('node.lifecycle.retired')
  }
}

export function lifecycleTone(lifecycle: NodeLifecycle): BadgeTone {
  return LIFECYCLE_TONES[lifecycle]
}

export function lifecycleSentence(lifecycle: NodeLifecycle): string {
  switch (lifecycle) {
    case 'CREATED':
      return t('node.lifecycle.sentence.created')
    case 'ENROLLED':
      return t('node.lifecycle.sentence.enrolled')
    case 'DRAINING':
      return t('node.lifecycle.sentence.draining')
    case 'DRAINED':
      return t('node.lifecycle.sentence.drained')
    case 'SUSPENDED':
      return t('node.lifecycle.sentence.suspended')
    case 'RETIRED':
      return t('node.lifecycle.sentence.retired')
  }
}

export function connectionLabel(state: NodeConnectionState): string {
  switch (state) {
    case 'CONNECTED':
      return t('node.connection.connected')
    case 'DEGRADED':
      return t('node.connection.degraded')
    case 'DISCONNECTED':
      return t('node.connection.disconnected')
  }
}

export function connectionTone(state: NodeConnectionState): BadgeTone {
  return CONNECTION_TONES[state]
}

export function tokenStateLabel(state: EnrolmentTokenState): string {
  switch (state) {
    case 'LIVE':
      return t('node.tokenState.live')
    case 'USED':
      return t('node.tokenState.used')
    case 'REVOKED':
      return t('node.tokenState.revoked')
    case 'EXPIRED':
      return t('node.tokenState.expired')
  }
}

export function tokenStateTone(state: EnrolmentTokenState): BadgeTone {
  return TOKEN_TONES[state]
}

export function outcomeLabel(outcome: DoctorOutcome): string {
  switch (outcome) {
    case 'PASS':
      return t('node.doctor.outcome.pass')
    case 'WARN':
      return t('node.doctor.outcome.warn')
    case 'FAIL':
      return t('node.doctor.outcome.fail')
    case 'UNSPECIFIED':
      return t('node.doctor.outcome.unspecified')
  }
}

export function outcomeTone(outcome: DoctorOutcome): BadgeTone {
  return OUTCOME_TONES[outcome]
}

export function severityLabel(severity: DoctorSeverity): string {
  switch (severity) {
    case 'REQUIRED':
      return t('node.doctor.severity.required')
    case 'ADVISORY':
      return t('node.doctor.severity.advisory')
    case 'UNSPECIFIED':
      return t('node.doctor.severity.unspecified')
  }
}

/**
 * The one line that answers "is this machine all right?".
 *
 * Order matters and it is the operator's, not the enum's: a suspended node is the thing to
 * read first even though it is also disconnected, and a node that has never enrolled is
 * not "offline" - it has not arrived.
 */
export function nodeSentence(node: NodeSummary): string {
  if (node.suspensionExplanation) {
    return node.suspensionExplanation
  }
  if (node.lifecycle === 'CREATED') {
    return lifecycleSentence('CREATED')
  }
  if (!node.connected) {
    return t('node.sentence.disconnected')
  }
  if (node.dockerHealthy === false) {
    return t('node.sentence.dockerUnhealthy')
  }
  if (node.reconcileError) {
    return t('node.sentence.reconcileFailed', {error: node.reconcileError})
  }
  if (!node.converged) {
    return t('node.sentence.catchingUp', {
      applied: node.appliedGeneration,
      desired: node.desiredGeneration,
    })
  }
  if (node.lifecycle === 'DRAINING') {
    return lifecycleSentence('DRAINING')
  }
  if (!node.schedulable) {
    return t('node.sentence.unschedulable')
  }
  return t('node.sentence.runningWorkloads', {
    running: node.runningWorkloadCount,
    total: node.workloadCount,
  })
}

/**
 * Whether the row belongs in the "deal with this" list on the fleet screen.
 *
 * Wider than the server's `attention` query on purpose: that one is suspended-or-draining,
 * and an operator also wants to see the machine whose Docker has gone, the one that never
 * finished catching up, and the one running without gVisor.
 */
export function needsAttention(node: NodeSummary): boolean {
  return (
    node.lifecycle === 'SUSPENDED' ||
    node.lifecycle === 'DRAINING' ||
    node.lessIsolated ||
    node.quotaAdvisory ||
    node.dockerHealthy === false ||
    node.reconcileError !== null ||
    (node.lifecycle === 'ENROLLED' && !node.connected)
  )
}

/** A UUID's first block: enough to tell two nodes apart in a fixed-width column. */
export function shortId(id: string): string {
  return id.slice(0, 8)
}

/**
 * A clock offset in words.
 *
 * Skew is the failure that points everywhere else - ACME fails, TLS handshakes fail, and
 * nothing in either message mentions the clock - so the number gets a sentence rather
 * than a raw millisecond count in a table cell.
 */
export function clockSkewSentence(millis: number | null): string | null {
  if (millis === null) {
    return null
  }
  const magnitude = Math.abs(millis)
  if (magnitude < 2_000) {
    return null
  }
  const seconds = Math.round(magnitude / 1000)
  const amount =
    seconds < 120
      ? t('node.clockSkew.seconds', {count: seconds})
      : t('node.clockSkew.minutes', {count: Math.round(seconds / 60)})
  const direction = millis > 0 ? t('node.clockSkew.ahead') : t('node.clockSkew.behind')
  return t('node.clockSkew.sentence', {amount, direction})
}
