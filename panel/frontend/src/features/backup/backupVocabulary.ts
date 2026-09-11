import type {BadgeTone} from '@/shell'

import type {
  BackupTargetKind,
  BackupView,
  DestinationKind,
  DestinationView,
  RestoreMode,
  RestorePointState,
  RestorePointTrigger,
  RestorePointView,
  RestoreRunView,
  RestoreState,
} from './backupTypes'

/**
 * What each backup enum is called on screen, and the sentences that make the numbers mean
 * something.
 *
 * The Java enums carry `label()` methods that Jackson does not serialise, so the words
 * live here. They match the Java strings deliberately: a flash message from the server and
 * a heading written here land next to each other often.
 *
 * The tone that matters most is the one for a policy that has never produced a snapshot.
 * "Enabled" is not the same as "working", and design §8.3 is explicit that a backup nobody
 * has restored is not a backup - so both of those show as warnings rather than as green.
 */

const TARGETS: Record<BackupTargetKind, string> = {
  VOLUME: 'Volume',
  DATABASE: 'Database',
}

const DESTINATIONS: Record<DestinationKind, string> = {
  S3: 'S3-compatible',
  LOCAL: 'On the node',
}

const POINT_STATES: Record<RestorePointState, string> = {
  RUNNING: 'Being taken',
  AVAILABLE: 'Available',
  FAILED: 'Failed',
  EXPIRED: 'Expired',
  DELETED: 'Deleted',
}

const POINT_TONES: Record<RestorePointState, BadgeTone> = {
  RUNNING: 'neutral',
  AVAILABLE: 'running',
  FAILED: 'failed',
  EXPIRED: 'neutral',
  DELETED: 'neutral',
}

const TRIGGERS: Record<RestorePointTrigger, string> = {
  SCHEDULED: 'Scheduled',
  MANUAL: 'Manual',
  PRE_RESTORE: 'Before restore',
}

const MODES: Record<RestoreMode, string> = {
  IN_PLACE: 'Restore in place',
  VERIFY: 'Verify only',
}

const RESTORE_STATES: Record<RestoreState, string> = {
  QUEUED: 'Queued',
  RUNNING: 'Running',
  SUCCEEDED: 'Succeeded',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
}

const RESTORE_TONES: Record<RestoreState, BadgeTone> = {
  QUEUED: 'neutral',
  RUNNING: 'accent',
  SUCCEEDED: 'running',
  FAILED: 'failed',
  CANCELLED: 'neutral',
}

export function targetKindLabel(kind: BackupTargetKind): string {
  return TARGETS[kind]
}

export function destinationKindLabel(kind: DestinationKind): string {
  return DESTINATIONS[kind]
}

export function pointStateLabel(state: RestorePointState): string {
  return POINT_STATES[state]
}

export function pointStateTone(state: RestorePointState): BadgeTone {
  return POINT_TONES[state]
}

export function triggerLabel(trigger: RestorePointTrigger): string {
  return TRIGGERS[trigger]
}

export function restoreModeLabel(mode: RestoreMode): string {
  return MODES[mode]
}

export function restoreStateLabel(state: RestoreState): string {
  return RESTORE_STATES[state]
}

export function restoreStateTone(state: RestoreState): BadgeTone {
  return RESTORE_TONES[state]
}

/** Mirrors `DestinationView.summary()`, which Jackson leaves in Java. */
export function destinationSummary(destination: DestinationView): string {
  return destination.kind === 'LOCAL'
    ? `on the node at ${destination.localPath ?? '(no path set)'}`
    : `${destination.bucket ?? '(no bucket)'} at ${destination.endpoint ?? '(no endpoint)'}`
}

/** The pill for a policy: what it last did, or what it has never done. */
export function policyTone(policy: BackupView): BadgeTone {
  if (policy.lastStatus === 'FAILED') {
    return 'failed'
  }
  if (policy.snapshotCount === 0) {
    return 'degraded'
  }
  if (!policy.enabled) {
    return 'neutral'
  }
  return policy.lastStatus === 'RUNNING' ? 'accent' : 'running'
}

/** The one line that answers "is this backup actually protecting anything?". */
export function policySentence(policy: BackupView): string {
  if (policy.lastStatus === 'FAILED') {
    return policy.lastError
      ? `The last run failed: ${policy.lastError}`
      : 'The last run failed and the node did not say why.'
  }
  if (policy.snapshotCount === 0) {
    return policy.scheduled
      ? 'This has never produced a snapshot. Run it once by hand rather than finding out on the day you need it.'
      : 'This has never run. It has no schedule, so nothing will happen until you press the button.'
  }
  if (!policy.enabled) {
    return `Switched off. Its ${policy.snapshotCount} existing ${policy.snapshotCount === 1 ? 'snapshot is' : 'snapshots are'} still there and still restorable.`
  }
  if (!policy.scheduled) {
    return `${policy.snapshotCount} ${policy.snapshotCount === 1 ? 'snapshot' : 'snapshots'} kept. No schedule, so it runs when you press the button.`
  }
  return `${policy.snapshotCount} ${policy.snapshotCount === 1 ? 'snapshot' : 'snapshots'} kept, newest first. Keeping ${policy.retentionCount} of them, or ${policy.retentionDays} days, whichever is smaller.`
}

/** The same for one snapshot, with the point of design §8.3 said out loud. */
export function snapshotSentence(snapshot: RestorePointView): string {
  if (snapshot.state === 'RUNNING') {
    return 'Still being written. It cannot be restored from until the node finishes and reports it.'
  }
  if (snapshot.state === 'FAILED') {
    return snapshot.errorMessage ?? 'This snapshot failed and the node did not say why.'
  }
  if (snapshot.state === 'EXPIRED' || snapshot.state === 'DELETED') {
    return 'Off the list. The archive itself goes when the node next prunes.'
  }
  if (snapshot.activeRestores > 0) {
    return `A restore from this snapshot is running right now.`
  }
  return snapshot.proven
    ? 'Restored successfully at least once, so this one is known to work.'
    : 'Never restored. A backup nobody has restored is a backup nobody knows about - verifying takes one press and touches nothing live.'
}

/** And for a destination, which is mostly about whether anybody has proved it works. */
export function destinationSentence(destination: DestinationView): string {
  if (destination.lastCheckError) {
    return `The last check failed: ${destination.lastCheckError}`
  }
  if (!destination.enabled) {
    return 'Switched off. Nothing new is written here; what is already stored stays.'
  }
  if (!destination.provenReachable) {
    return 'Never checked. Press Check before you point a backup at it - a destination that cannot be written to fails silently at three in the morning.'
  }
  return destination.kind === 'LOCAL'
    ? 'Reachable. It lives on the node, so it survives a lost container and not a lost machine.'
    : 'Reachable, and offsite.'
}

/** How a restore is going, in the words somebody watching it needs. */
export function restoreSentence(restore: RestoreRunView): string {
  switch (restore.state) {
    case 'QUEUED':
      return 'Queued. A node picks it up on its next pass.'
    case 'RUNNING':
      return restore.mode === 'IN_PLACE'
        ? 'Restoring over the live data. A snapshot of what was there was taken first.'
        : 'Restoring somewhere disposable to prove the archive is good. Nothing live is touched.'
    case 'SUCCEEDED':
      return restore.mode === 'IN_PLACE'
        ? 'Done. The data is back as it was in the snapshot.'
        : 'Done. This snapshot restores, so it is a backup you can rely on.'
    case 'FAILED':
      return restore.errorMessage ?? 'It failed, and the node did not say why.'
    default:
      return 'Cancelled before it finished.'
  }
}

/**
 * A cron expression in words, for the five shapes the panel's own form produces.
 *
 * Not a general cron parser, and it says so: anything it does not recognise is shown
 * verbatim rather than described wrongly. A schedule described incorrectly is worse than
 * one not described at all.
 */
export function scheduleSentence(schedule: string | null, timezone: string | null): string {
  if (schedule === null || schedule.trim().length === 0) {
    return 'No schedule - runs only when you press the button'
  }
  const zone = timezone ? ` ${timezone}` : ''
  const fields = schedule.trim().split(/\s+/)
  if (fields.length !== 5) {
    return schedule
  }
  const [minute, hour, dayOfMonth, month, dayOfWeek] = fields as [
    string,
    string,
    string,
    string,
    string,
  ]
  const at = `${hour.padStart(2, '0')}:${minute.padStart(2, '0')}`
  const numeric = /^\d+$/

  if (!numeric.test(minute) || !numeric.test(hour) || month !== '*') {
    return schedule
  }
  if (dayOfMonth === '*' && dayOfWeek === '*') {
    return `Every day at ${at}${zone}`
  }
  if (dayOfMonth === '*' && numeric.test(dayOfWeek)) {
    return `Every ${weekday(Number(dayOfWeek))} at ${at}${zone}`
  }
  if (numeric.test(dayOfMonth) && dayOfWeek === '*') {
    return `On day ${dayOfMonth} of every month at ${at}${zone}`
  }
  return schedule
}

const WEEKDAYS = [
  'Sunday',
  'Monday',
  'Tuesday',
  'Wednesday',
  'Thursday',
  'Friday',
  'Saturday',
] as const

function weekday(index: number): string {
  return WEEKDAYS[index % 7] ?? 'day'
}
