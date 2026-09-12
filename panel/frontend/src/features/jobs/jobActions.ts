import {router} from '@inertiajs/react'

import {askConfirmation} from '@/shell'
import {t} from '@/i18n'

import type {FailedJob} from './jobTypes'

/**
 * The two things an operator can do to a stuck job, each behind the question worth asking.
 *
 * <p>They are opposites and the wording has to make that obvious at a glance, because the
 * buttons sit next to each other on a row and the rows look alike. Retrying is safe and
 * repeatable. Discarding is not: db-scheduler deletes the row, the work is simply not done,
 * and nothing anywhere will notice - a deployment stays queued for ever, a backup never
 * runs. So one confirmation states the effect, and the other states the loss.
 */

/**
 * `POST /admin/jobs/retry` - run it now, clearing its backoff and its failure streak.
 *
 * <p>Worth a confirmation even though it is safe, because the job is failing for a reason
 * and pressing this in a loop is how a broken job becomes a busy panel. The wording says
 * what will happen rather than asking whether the operator is sure.
 */
export async function retryJob(job: FailedJob): Promise<void> {
  const confirmed = await askConfirmation({
    title: t('jobs.action.retry.title', {task: job.taskName}),
    body: t('jobs.action.retry.body', {failures: job.consecutiveFailures}),
    confirmLabel: t('jobs.action.retry.confirm'),
    cancelLabel: t('jobs.action.retry.cancel'),
  })
  if (!confirmed) {
    return
  }
  router.post(
    '/admin/jobs/retry',
    {taskName: job.taskName, instanceId: job.instanceId},
    {preserveScroll: true},
  )
}

/**
 * `POST /admin/jobs/discard` - abandon a job that will never succeed.
 *
 * <p>The typed confirmation is the task name. This is the only irreversible button on the
 * screen and the row it belongs to is one of many identical-looking rows, so the operator
 * has to name what they are throwing away rather than tapping in the right place.
 */
export async function discardJob(job: FailedJob): Promise<void> {
  const confirmed = await askConfirmation({
    title: t('jobs.action.discard.title', {task: job.taskName}),
    body: t('jobs.action.discard.body'),
    confirmLabel: t('jobs.action.discard.confirm'),
    cancelLabel: t('jobs.action.discard.cancel'),
    tone: 'danger',
    requireText: job.taskName,
    requireTextLabel: t('jobs.action.discard.requireTextLabel'),
  })
  if (!confirmed) {
    return
  }
  router.post(
    '/admin/jobs/discard',
    {taskName: job.taskName, instanceId: job.instanceId},
    {preserveScroll: true},
  )
}
