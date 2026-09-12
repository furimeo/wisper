import {t} from '@/i18n'
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

const POINT_TONES: Record<RestorePointState, BadgeTone> = {
  RUNNING: 'neutral',
  AVAILABLE: 'running',
  FAILED: 'failed',
  EXPIRED: 'neutral',
  DELETED: 'neutral',
}

const RESTORE_TONES: Record<RestoreState, BadgeTone> = {
  QUEUED: 'neutral',
  RUNNING: 'accent',
  SUCCEEDED: 'running',
  FAILED: 'failed',
  CANCELLED: 'neutral',
}

export function targetKindLabel(kind: BackupTargetKind): string {
  switch (kind) {
    case 'VOLUME':
      return t('backup.target.volume')
    case 'DATABASE':
      return t('backup.target.database')
  }
}

export function destinationKindLabel(kind: DestinationKind): string {
  switch (kind) {
    case 'S3':
      return t('backup.destKind.s3')
    case 'LOCAL':
      return t('backup.destKind.local')
  }
}

export function pointStateLabel(state: RestorePointState): string {
  switch (state) {
    case 'RUNNING':
      return t('backup.pointState.running')
    case 'AVAILABLE':
      return t('backup.pointState.available')
    case 'FAILED':
      return t('backup.pointState.failed')
    case 'EXPIRED':
      return t('backup.pointState.expired')
    case 'DELETED':
      return t('backup.pointState.deleted')
  }
}

export function pointStateTone(state: RestorePointState): BadgeTone {
  return POINT_TONES[state]
}

export function triggerLabel(trigger: RestorePointTrigger): string {
  switch (trigger) {
    case 'SCHEDULED':
      return t('backup.trigger.scheduled')
    case 'MANUAL':
      return t('backup.trigger.manual')
    case 'PRE_RESTORE':
      return t('backup.trigger.preRestore')
  }
}

export function restoreModeLabel(mode: RestoreMode): string {
  switch (mode) {
    case 'IN_PLACE':
      return t('backup.restoreMode.inPlace')
    case 'VERIFY':
      return t('backup.restoreMode.verify')
  }
}

export function restoreStateLabel(state: RestoreState): string {
  switch (state) {
    case 'QUEUED':
      return t('backup.restoreState.queued')
    case 'RUNNING':
      return t('backup.restoreState.running')
    case 'SUCCEEDED':
      return t('backup.restoreState.succeeded')
    case 'FAILED':
      return t('backup.restoreState.failed')
    case 'CANCELLED':
      return t('backup.restoreState.cancelled')
  }
}

export function restoreStateTone(state: RestoreState): BadgeTone {
  return RESTORE_TONES[state]
}

/** Mirrors `DestinationView.summary()`, which Jackson leaves in Java. */
export function destinationSummary(destination: DestinationView): string {
  return destination.kind === 'LOCAL'
    ? t('backup.destSummary.local', {path: destination.localPath ?? '(no path set)'})
    : t('backup.destSummary.s3', {
        bucket: destination.bucket ?? '(no bucket)',
        endpoint: destination.endpoint ?? '(no endpoint)',
      })
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
      ? t('backup.sentence.failed', {error: policy.lastError})
      : t('backup.sentence.failedDefault')
  }
  if (policy.snapshotCount === 0) {
    return policy.scheduled
      ? t('backup.sentence.neverRunScheduled')
      : t('backup.sentence.neverRunManual')
  }
  if (!policy.enabled) {
    return policy.snapshotCount === 1
      ? t('backup.sentence.disabled', {count: policy.snapshotCount})
      : t('backup.sentence.disabledPlural', {count: policy.snapshotCount})
  }
  if (!policy.scheduled) {
    return policy.snapshotCount === 1
      ? t('backup.sentence.manualKept', {count: policy.snapshotCount})
      : t('backup.sentence.manualKeptPlural', {count: policy.snapshotCount})
  }
  return policy.snapshotCount === 1
    ? t('backup.sentence.scheduledKept', {
        count: policy.snapshotCount,
        retention: policy.retentionCount,
        days: policy.retentionDays,
      })
    : t('backup.sentence.scheduledKeptPlural', {
        count: policy.snapshotCount,
        retention: policy.retentionCount,
        days: policy.retentionDays,
      })
}

/** The same for one snapshot, with the point of design §8.3 said out loud. */
export function snapshotSentence(snapshot: RestorePointView): string {
  if (snapshot.state === 'RUNNING') {
    return t('backup.snapshotSentence.running')
  }
  if (snapshot.state === 'FAILED') {
    return snapshot.errorMessage ?? t('backup.snapshotSentence.failed')
  }
  if (snapshot.state === 'EXPIRED' || snapshot.state === 'DELETED') {
    return t('backup.snapshotSentence.deleted')
  }
  if (snapshot.activeRestores > 0) {
    return t('backup.snapshotSentence.activeRestore')
  }
  return snapshot.proven
    ? t('backup.snapshotSentence.proven')
    : t('backup.snapshotSentence.unproven')
}

/** And for a destination, which is mostly about whether anybody has proved it works. */
export function destinationSentence(destination: DestinationView): string {
  if (destination.lastCheckError) {
    return t('backup.destSentence.failed', {error: destination.lastCheckError})
  }
  if (!destination.enabled) {
    return t('backup.destSentence.disabled')
  }
  if (!destination.provenReachable) {
    return t('backup.destSentence.unproven')
  }
  return destination.kind === 'LOCAL'
    ? t('backup.destSentence.localReachable')
    : t('backup.destSentence.s3Reachable')
}

/** How a restore is going, in the words somebody watching it needs. */
export function restoreSentence(restore: RestoreRunView): string {
  switch (restore.state) {
    case 'QUEUED':
      return t('backup.restoreSentence.queued')
    case 'RUNNING':
      return restore.mode === 'IN_PLACE'
        ? t('backup.restoreSentence.runningInPlace')
        : t('backup.restoreSentence.runningVerify')
    case 'SUCCEEDED':
      return restore.mode === 'IN_PLACE'
        ? t('backup.restoreSentence.succeededInPlace')
        : t('backup.restoreSentence.succeededVerify')
    case 'FAILED':
      return restore.errorMessage ?? t('backup.restoreSentence.failed')
    default:
      return t('backup.restoreSentence.cancelled')
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
    return t('backup.schedule.noSchedule')
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
    return t('backup.schedule.everyDay', {time: at, zone})
  }
  if (dayOfMonth === '*' && numeric.test(dayOfWeek)) {
    return t('backup.schedule.everyWeekday', {day: weekday(Number(dayOfWeek)), time: at, zone})
  }
  if (numeric.test(dayOfMonth) && dayOfWeek === '*') {
    return t('backup.schedule.monthly', {day: dayOfMonth, time: at, zone})
  }
  return schedule
}

function weekday(index: number): string {
  return t(`backup.weekday.${index % 7}` as 'backup.weekday.0')
}
