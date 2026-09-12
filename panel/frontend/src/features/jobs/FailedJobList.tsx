import {Badge, DataList, EmptyState, RelativeTime} from '@/shell'
import type {SwipeAction} from '@/shell'
import {t} from '@/i18n'

import {discardJob, retryJob} from './jobActions'
import type {FailedJob} from './jobTypes'
import {domainOf, hasEverSucceeded} from './jobTypes'

/**
 * Every job with a current failure streak, newest failure first.
 *
 * <p>Two facts decide what an operator does next and both are on the row. "Never worked"
 * against "worked until Tuesday" separates a job that was mis-wired from one broken by a
 * change, and those are different investigations. And a picked job is one a worker is
 * holding right now: neither button applies to it, because the row is not the work, and
 * offering them would be offering to delete a record of something still running.
 */
export function FailedJobList({jobs}: {jobs: FailedJob[]}) {
  if (jobs.length === 0) {
    return (
      <EmptyState
        title={t('jobs.list.empty.title')}
        description={t('jobs.list.empty.description')}
      />
    )
  }

  const actionsFor = (job: FailedJob): SwipeAction[] =>
    job.actionable
      ? [
          {label: t('jobs.list.action.runNow'), onSelect: () => void retryJob(job)},
          {label: t('jobs.list.action.discard'), tone: 'danger', onSelect: () => void discardJob(job)},
        ]
      : []

  return (
    <DataList
      items={jobs}
      label={t('jobs.unit')}
      keyOf={(job) => `${job.taskName}:${job.instanceId}`}
      primary={(job) => job.taskName}
      secondary={(job) => (
        <span className="break-all">
          {domainOf(job)} · {job.instanceId}
        </span>
      )}
      trailing={(job) => <Streak job={job} />}
      actions={actionsFor}
      columns={[
        {key: 'task', header: t('jobs.list.column.task'), cell: (job) => job.taskName},
        {
          key: 'instance',
          header: t('jobs.list.column.instance'),
          cell: (job) => <span className="break-all font-mono text-xs">{job.instanceId}</span>,
        },
        {key: 'streak', header: t('jobs.list.column.failures'), align: 'right', cell: (job) => <Streak job={job} />},
        {
          key: 'lastFailure',
          header: t('jobs.list.column.lastFailure'),
          cell: (job) => <RelativeTime at={job.lastFailure} fallback="unknown" />,
        },
        {
          key: 'history',
          header: t('jobs.list.column.history'),
          cell: (job) =>
            hasEverSucceeded(job) ? (
              <span>
                {t('jobs.list.history.worked')} <RelativeTime at={job.lastSuccess} />
              </span>
            ) : (
              <Badge tone="degraded">{t('jobs.list.history.never')}</Badge>
            ),
        },
        {
          key: 'next',
          header: t('jobs.list.column.nextAttempt'),
          cell: (job) =>
            job.picked ? (
              <Badge tone="running">
                {t('jobs.list.runningOn', {worker: job.pickedBy ?? t('jobs.list.workerFallback')})}
              </Badge>
            ) : (
              <RelativeTime at={job.executionTime} />
            ),
        },
      ]}
    />
  )
}

/**
 * The streak, as a number that reads as a severity.
 *
 * <p>One failure is noise - a node restarted, a lock was held. Ten in a row is a job that
 * cannot succeed, and db-scheduler has by then pushed its next attempt hours out, so it
 * will not resolve itself while somebody watches.
 */
function Streak({job}: {job: FailedJob}) {
  return (
    <Badge tone={job.consecutiveFailures >= 5 ? 'failed' : 'degraded'}>
      {t('jobs.list.streak', {count: job.consecutiveFailures})}
    </Badge>
  )
}
