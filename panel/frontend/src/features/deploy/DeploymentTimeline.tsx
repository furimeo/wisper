import {t} from '@/i18n'
import {Card, Icon, RelativeTime, Spinner, cx} from '@/shell'

import type {DeploymentStatus, DeploymentSummary} from './deployTypes'
import {statusLabel, statusSentence} from './deployVocabulary'

type StepState = 'done' | 'active' | 'stopped' | 'pending'

interface Step {
  rank: number
  label: string
  detail: string
  at: string | null
  state: StepState
}

const RANK: Record<DeploymentStatus, number> = {
  QUEUED: 0,
  ASSIGNED: 1,
  BUILDING: 2,
  PUBLISHING: 3,
  SUCCEEDED: 4,
  FAILED: 4,
  CANCELLED: 4,
  SUPERSEDED: 4,
}

const STOPPED_EARLY: readonly DeploymentStatus[] = ['FAILED', 'CANCELLED', 'SUPERSEDED']

export function DeploymentTimeline({deployment}: {deployment: DeploymentSummary}) {
  const steps = stepsFor(deployment)

  return (
    <Card title={t('deploy.timeline.title')} description={statusSentence(deployment.status)}>
      <ol className="flex flex-col">
        {steps.map((step, index) => (
          <li key={step.rank} className="flex gap-3">
            <span className="flex w-6 shrink-0 flex-col items-center">
              <Marker state={step.state} />
              {index < steps.length - 1 ? (
                <span
                  aria-hidden="true"
                  className={cx(
                    'w-px flex-1',
                    step.state === 'done'
                      ? 'bg-running/50'
                      : 'bg-ink-200 dark:bg-ink-700',
                  )}
                />
              ) : null}
            </span>

            <div className={cx('min-w-0 flex-1', index < steps.length - 1 ? 'pb-4' : '')}>
              <div className="flex flex-wrap items-baseline justify-between gap-x-3">
                <span
                  className={cx(
                    'text-sm font-medium',
                    step.state === 'pending'
                      ? 'text-ink-400 dark:text-ink-500'
                      : 'text-ink-900 dark:text-ink-100',
                  )}
                >
                  {step.label}
                </span>
                {step.at ? (
                  <RelativeTime
                    at={step.at}
                    className="text-xs text-ink-500 dark:text-ink-400"
                  />
                ) : null}
              </div>
              <p
                className={cx(
                  'mt-0.5 text-sm leading-relaxed',
                  step.state === 'stopped'
                    ? 'text-failed'
                    : 'text-ink-500 dark:text-ink-400',
                )}
              >
                {step.detail}
              </p>
            </div>
          </li>
        ))}
      </ol>
    </Card>
  )
}

function Marker({state}: {state: StepState}) {
  if (state === 'active') {
    return (
      <span className="flex size-6 items-center justify-center text-accent-500">
        <Spinner />
      </span>
    )
  }
  if (state === 'done') {
    return (
      <span className="flex size-6 items-center justify-center rounded-full bg-running/15 text-running">
        <Icon name="check" className="size-4" />
      </span>
    )
  }
  if (state === 'stopped') {
    return (
      <span className="flex size-6 items-center justify-center rounded-full bg-failed/15 text-failed">
        <Icon name="close" className="size-4" />
      </span>
    )
  }
  return (
    <span
      aria-hidden="true"
      className="mt-2 size-2 rounded-full border border-ink-300 dark:border-ink-600"
    />
  )
}

function stepsFor(deployment: DeploymentSummary): Step[] {
  const current = RANK[deployment.status]
  const halted = STOPPED_EARLY.includes(deployment.status)
  const reached = furthestReached(deployment)
  const app = deployment.source === 'IMAGE'
  const restored = deployment.trigger === 'ROLLBACK'

  const state = (rank: number): StepState => {
    if (halted) {
      if (rank <= reached) {
        return 'done'
      }
      return rank === reached + 1 || rank === 4 ? 'stopped' : 'pending'
    }
    if (rank < current) {
      return 'done'
    }
    return rank === current ? (rank === 4 ? 'done' : 'active') : 'pending'
  }

  const steps: Step[] = [
    {
      rank: 0,
      label: t('deploy.timeline.queued_label'),
      detail: t('deploy.timeline.queued_detail'),
      at: deployment.queuedAt,
      state: state(0),
    },
    {
      rank: 1,
      label: t('deploy.timeline.assigned_label'),
      detail:
        deployment.nodeId === null
          ? t('deploy.timeline.assigned_detail_waiting')
          : t('deploy.timeline.assigned_detail_done'),
      at: null,
      state: state(1),
    },
  ]

  if (!app && !restored) {
    steps.push({
      rank: 2,
      label: t('deploy.timeline.building_label'),
      detail: t('deploy.timeline.building_detail'),
      at: deployment.startedAt,
      state: state(2),
    })
  }

  steps.push({
    rank: 3,
    label: t('deploy.timeline.publishing_label'),
    detail: app
      ? t('deploy.timeline.publishing_detail_app')
      : restored
        ? t('deploy.timeline.publishing_detail_rollback')
        : t('deploy.timeline.publishing_detail_site'),
    at: app ? deployment.startedAt : null,
    state: state(3),
  })

  steps.push({
    rank: 4,
    label: outcomeLabel(deployment),
    detail: outcomeDetail(deployment),
    at: deployment.finishedAt,
    state: state(4),
  })

  return steps
}

function furthestReached(deployment: DeploymentSummary): number {
  if (deployment.status === 'SUPERSEDED') {
    return 0
  }
  if (deployment.nodeId === null) {
    return 0
  }
  return deployment.startedAt === null ? 1 : 2
}

function outcomeLabel(deployment: DeploymentSummary): string {
  if (deployment.status === 'SUCCEEDED') {
    return deployment.current ? t('deploy.timeline.live_label') : statusLabel('SUCCEEDED')
  }
  if (STOPPED_EARLY.includes(deployment.status)) {
    return statusLabel(deployment.status)
  }
  return t('deploy.timeline.finished_label')
}

function outcomeDetail(deployment: DeploymentSummary): string {
  if (deployment.errorMessage) {
    return deployment.errorMessage
  }
  if (deployment.status === 'SUCCEEDED') {
    return deployment.current
      ? t('deploy.timeline.detail_live')
      : t('deploy.timeline.detail_superseded_past')
  }
  if (STOPPED_EARLY.includes(deployment.status)) {
    return statusSentence(deployment.status)
  }
  return t('deploy.timeline.detail_not_finished')
}
