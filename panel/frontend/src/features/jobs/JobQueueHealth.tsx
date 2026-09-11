import {Badge, Card, CardFact, CardFacts} from '@/shell'

import type {JobQueueSummary} from './jobTypes'

/**
 * The four counters, and the one sentence that says whether they are a problem.
 *
 * <p>`due` is the number worth understanding and the reason this card exists at all. A
 * queue with work in it is normal; a queue whose *due* count keeps climbing means nothing
 * is picking work up - the scheduler is wedged, or this panel was started with jobs
 * disabled - and every symptom of that shows up somewhere else first: deployments that
 * stay queued, backups that never run, a node whose spec is never republished. Naming it
 * here is what turns four unexplained tickets into one.
 */
export function JobQueueHealth({summary}: {summary: JobQueueSummary}) {
  const stopped = summary.due > 0 && summary.running === 0

  return (
    <Card
      title="Queue"
      description={
        summary.healthy
          ? 'Nothing is failing.'
          : `${summary.failing} ${summary.failing === 1 ? 'job has' : 'jobs have'} a current failure streak.`
      }
    >
      <CardFacts>
        <CardFact label="Queued">{summary.queued.toLocaleString()}</CardFact>
        <CardFact label="Due">
          {summary.due > 0 ? (
            <Badge tone="degraded">{summary.due.toLocaleString()}</Badge>
          ) : (
            '0'
          )}
        </CardFact>
        <CardFact label="Running">{summary.running.toLocaleString()}</CardFact>
        <CardFact label="Failing">
          {summary.failing > 0 ? (
            <Badge tone="failed">{summary.failing.toLocaleString()}</Badge>
          ) : (
            '0'
          )}
        </CardFact>
      </CardFacts>

      {stopped ? (
        <p className="mt-3 rounded-xl border border-degraded/50 bg-degraded/10 px-4 py-3 text-sm leading-relaxed">
          Work is due and no worker has picked any of it up. That is not a slow queue, it is
          a stopped one: check that this panel is running with its scheduler enabled, and
          that nothing else is holding the lock.
        </p>
      ) : null}
    </Card>
  )
}
