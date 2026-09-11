import {Badge, DataList, EmptyState, RelativeTime} from '@/shell'
import type {SwipeAction} from '@/shell'

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
        title="Nothing is failing"
        description="Every scheduled job either succeeded or has not run into trouble yet."
      />
    )
  }

  const actionsFor = (job: FailedJob): SwipeAction[] =>
    job.actionable
      ? [
          {label: 'Run now', onSelect: () => void retryJob(job)},
          {label: 'Discard', tone: 'danger', onSelect: () => void discardJob(job)},
        ]
      : []

  return (
    <DataList
      items={jobs}
      label="failing jobs"
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
        {key: 'task', header: 'Task', cell: (job) => job.taskName},
        {
          key: 'instance',
          header: 'Instance',
          cell: (job) => <span className="break-all font-mono text-xs">{job.instanceId}</span>,
        },
        {key: 'streak', header: 'Failures', align: 'right', cell: (job) => <Streak job={job} />},
        {
          key: 'lastFailure',
          header: 'Last failure',
          cell: (job) => <RelativeTime at={job.lastFailure} fallback="unknown" />,
        },
        {
          key: 'history',
          header: 'History',
          cell: (job) =>
            hasEverSucceeded(job) ? (
              <span>
                worked <RelativeTime at={job.lastSuccess} />
              </span>
            ) : (
              <Badge tone="degraded">never succeeded</Badge>
            ),
        },
        {
          key: 'next',
          header: 'Next attempt',
          cell: (job) =>
            job.picked ? (
              <Badge tone="running">running on {job.pickedBy ?? 'a worker'}</Badge>
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
      {job.consecutiveFailures} in a row
    </Badge>
  )
}
