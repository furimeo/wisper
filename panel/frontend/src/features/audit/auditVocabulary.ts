import type {BadgeTone} from '@/shell'

import type {AuditActorKind, AuditLogEntry, AuditOutcome} from './auditTypes'

/**
 * Turning the trail's identifiers into something a person reads at speed.
 *
 * Nothing here is a hard-coded list of actions. The vocabulary arrives as a prop -
 * `AuditAction.byDomain()` derives it from the list in `panel-ports.md` §2.4 - and a copy
 * of it in TypeScript would be a second list that silently stops matching the first. What
 * this file does instead is *shape* whatever arrives: split `service.stop` into its two
 * halves and give each half a readable form, so a new action added on the server appears
 * here correctly on the day it is added.
 */

const OUTCOMES: Record<AuditOutcome, string> = {
  SUCCEEDED: 'Succeeded',
  FAILED: 'Failed',
  DENIED: 'Refused',
}

const OUTCOME_TONES: Record<AuditOutcome, BadgeTone> = {
  SUCCEEDED: 'running',
  FAILED: 'failed',
  DENIED: 'degraded',
}

const ACTORS: Record<AuditActorKind, string> = {
  ACCOUNT: 'Person',
  API_TOKEN: 'API token',
  NODE: 'Node',
  SYSTEM: 'The platform',
}

export function outcomeLabel(outcome: AuditOutcome): string {
  return OUTCOMES[outcome]
}

export function outcomeTone(outcome: AuditOutcome): BadgeTone {
  return OUTCOME_TONES[outcome]
}

export function actorKindLabel(kind: AuditActorKind): string {
  return ACTORS[kind]
}

/** `service` out of `service.stop`. Empty for anything without a dot. */
export function domainOf(action: string): string {
  const dot = action.indexOf('.')
  return dot < 0 ? action : action.slice(0, dot)
}

/**
 * `service.stop` as "Stop", and `quota_override.grant` as "Grant".
 *
 * Only the verb, because the object it acted on is already the next column and repeating
 * it costs the width a phone does not have.
 */
export function verbOf(action: string): string {
  const dot = action.indexOf('.')
  const verb = dot < 0 ? action : action.slice(dot + 1)
  return sentenceCase(verb)
}

/** `api_token` as "API token", `restore_point` as "Restore point". */
export function domainLabel(domain: string): string {
  if (domain === 'api_token') {
    return 'API token'
  }
  return sentenceCase(domain)
}

/** Mirrors `AuditLogEntry.target()`, which Jackson leaves in Java. */
export function targetOf(entry: AuditLogEntry): string {
  if (entry.targetKind === null) {
    return '-'
  }
  if (entry.targetLabel && entry.targetLabel.trim().length > 0) {
    return `${domainLabel(entry.targetKind)} ${entry.targetLabel}`
  }
  return entry.targetId === null
    ? domainLabel(entry.targetKind)
    : `${domainLabel(entry.targetKind)} ${entry.targetId.slice(0, 8)}`
}

/** One row as a sentence, for the phone layout where there are no columns to read. */
export function entrySentence(entry: AuditLogEntry): string {
  const verb = verbOf(entry.action).toLowerCase()
  const what = entry.targetKind === null ? verb : `${verb} ${targetOf(entry)}`
  switch (entry.outcome) {
    case 'DENIED':
      return `${entry.actorLabel} was refused permission to ${what}.`
    case 'FAILED':
      return `${entry.actorLabel} tried to ${what} and it failed.`
    default:
      return `${entry.actorLabel} did ${what}.`
  }
}

function sentenceCase(value: string): string {
  const words = value.split('_').filter((word) => word.length > 0)
  if (words.length === 0) {
    return value
  }
  const [first = '', ...rest] = words
  return [first.charAt(0).toUpperCase() + first.slice(1), ...rest].join(' ')
}

/**
 * An `Instant` as the value a `datetime-local` input wants, and back.
 *
 * The server parses `from` and `to` with `Instant.parse`, which needs the `Z`. The input
 * gives a local wall-clock string with no zone at all, so both directions have to go
 * through a `Date` - and an operator filtering "since nine this morning" means nine where
 * they are, not nine in UTC.
 */
export function toLocalInput(instant: string | null): string {
  if (instant === null) {
    return ''
  }
  const parsed = Date.parse(instant)
  if (!Number.isFinite(parsed)) {
    return ''
  }
  const date = new Date(parsed)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** The reverse. An unparseable value becomes empty rather than "now". */
export function fromLocalInput(local: string): string {
  if (local.trim().length === 0) {
    return ''
  }
  const parsed = Date.parse(local)
  return Number.isFinite(parsed) ? new Date(parsed).toISOString() : ''
}
