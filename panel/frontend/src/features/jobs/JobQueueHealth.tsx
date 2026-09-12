import {Badge, Card, CardFact, CardFacts} from '@/shell'
import {t} from '@/i18n'

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
      title={t('jobs.health.title')}
      description={
        summary.healthy
          ? t('jobs.health.healthy')
          : t('jobs.health.failing', {count: summary.failing})
      }
    >
      <CardFacts>
        <CardFact label={t('jobs.health.queued')}>{summary.queued.toLocaleString()}</CardFact>
        <CardFact label={t('jobs.health.due')}>
          {summary.due > 0 ? (
            <Badge tone="degraded">{summary.due.toLocaleString()}</Badge>
          ) : (
            '0'
          )}
        </CardFact>
        <CardFact label={t('jobs.health.running')}>{summary.running.toLocaleString()}</CardFact>
        <CardFact label={t('jobs.health.failingLabel')}>
          {summary.failing > 0 ? (
            <Badge tone="failed">{summary.failing.toLocaleString()}</Badge>
          ) : (
            '0'
          )}
        </CardFact>
      </CardFacts>

      {stopped ? (
        <p className="mt-3 rounded-xl border border-degraded/50 bg-degraded/10 px-4 py-3 text-sm leading-relaxed">
          {t('jobs.health.stoppedWarning')}
        </p>
      ) : null}
    </Card>
  )
}
