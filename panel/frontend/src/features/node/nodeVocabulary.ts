import type {BadgeTone} from '@/shell'

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

const LIFECYCLES: Record<NodeLifecycle, string> = {
  CREATED: 'Awaiting enrolment',
  ENROLLED: 'Enrolled',
  DRAINING: 'Draining',
  DRAINED: 'Drained',
  SUSPENDED: 'Suspended',
  RETIRED: 'Retired',
}

const LIFECYCLE_TONES: Record<NodeLifecycle, BadgeTone> = {
  CREATED: 'neutral',
  ENROLLED: 'running',
  DRAINING: 'degraded',
  DRAINED: 'neutral',
  SUSPENDED: 'failed',
  RETIRED: 'neutral',
}

const LIFECYCLE_SENTENCES: Record<NodeLifecycle, string> = {
  CREATED:
    'The record exists and no machine has enrolled against it yet. Issue a bootstrap token and run the install command on the machine.',
  ENROLLED: 'Enrolled and under management.',
  DRAINING:
    'Moving everything it can to other nodes. Anything holding a volume stays put and is listed for you to decide about.',
  DRAINED: 'Everything that could move has moved. What is left is pinned by a volume.',
  SUSPENDED: 'The panel is not publishing to this machine. Whatever is running keeps running.',
  RETIRED: 'Out of the fleet. Nothing is scheduled here.',
}

const CONNECTION_STATES: Record<NodeConnectionState, string> = {
  CONNECTED: 'Connected',
  DEGRADED: 'Degraded',
  DISCONNECTED: 'Offline',
}

const CONNECTION_TONES: Record<NodeConnectionState, BadgeTone> = {
  CONNECTED: 'running',
  DEGRADED: 'degraded',
  DISCONNECTED: 'neutral',
}

const TOKEN_STATES: Record<EnrolmentTokenState, string> = {
  LIVE: 'Live',
  USED: 'Used',
  REVOKED: 'Revoked',
  EXPIRED: 'Expired',
}

const TOKEN_TONES: Record<EnrolmentTokenState, BadgeTone> = {
  LIVE: 'accent',
  USED: 'running',
  REVOKED: 'neutral',
  EXPIRED: 'neutral',
}

const OUTCOMES: Record<DoctorOutcome, string> = {
  PASS: 'Pass',
  WARN: 'Warning',
  FAIL: 'Fail',
  UNSPECIFIED: 'Not reported',
}

const OUTCOME_TONES: Record<DoctorOutcome, BadgeTone> = {
  PASS: 'running',
  WARN: 'degraded',
  FAIL: 'failed',
  UNSPECIFIED: 'neutral',
}

const SEVERITIES: Record<DoctorSeverity, string> = {
  REQUIRED: 'Required',
  ADVISORY: 'Advisory',
  UNSPECIFIED: 'Unclassified',
}

export function lifecycleLabel(lifecycle: NodeLifecycle): string {
  return LIFECYCLES[lifecycle]
}

export function lifecycleTone(lifecycle: NodeLifecycle): BadgeTone {
  return LIFECYCLE_TONES[lifecycle]
}

export function lifecycleSentence(lifecycle: NodeLifecycle): string {
  return LIFECYCLE_SENTENCES[lifecycle]
}

export function connectionLabel(state: NodeConnectionState): string {
  return CONNECTION_STATES[state]
}

export function connectionTone(state: NodeConnectionState): BadgeTone {
  return CONNECTION_TONES[state]
}

export function tokenStateLabel(state: EnrolmentTokenState): string {
  return TOKEN_STATES[state]
}

export function tokenStateTone(state: EnrolmentTokenState): BadgeTone {
  return TOKEN_TONES[state]
}

export function outcomeLabel(outcome: DoctorOutcome): string {
  return OUTCOMES[outcome]
}

export function outcomeTone(outcome: DoctorOutcome): BadgeTone {
  return OUTCOME_TONES[outcome]
}

export function severityLabel(severity: DoctorSeverity): string {
  return SEVERITIES[severity]
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
    return LIFECYCLE_SENTENCES.CREATED
  }
  if (!node.connected) {
    return 'No control stream. Its containers keep running and it reconnects on its own; the panel just cannot publish to it in the meantime.'
  }
  if (node.dockerHealthy === false) {
    return 'The node cannot reach Docker. It is retrying and it has deleted nothing - "cannot see it" is not "does not exist".'
  }
  if (node.reconcileError) {
    return `The last reconcile failed: ${node.reconcileError}`
  }
  if (!node.converged) {
    return `Catching up: generation ${node.appliedGeneration} applied of ${node.desiredGeneration} published. It reconciles every fifteen seconds.`
  }
  if (node.lifecycle === 'DRAINING') {
    return LIFECYCLE_SENTENCES.DRAINING
  }
  if (!node.schedulable) {
    return 'Running normally, and taking no new placements because you asked it not to.'
  }
  return `Running ${node.runningWorkloadCount} of ${node.workloadCount} workloads, all at the published generation.`
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
  const amount = seconds < 120 ? `${seconds} seconds` : `${Math.round(seconds / 60)} minutes`
  return `This machine's clock is ${amount} ${millis > 0 ? 'ahead of' : 'behind'} the panel's. Certificates and ACME break in ways that point somewhere else entirely; fix time sync on the node.`
}
