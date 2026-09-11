import {Card, Icon, RelativeTime, Spinner, cx} from '@/shell'

import type {DeploymentStatus, DeploymentSummary} from './deployTypes'
import {statusLabel, statusSentence} from './deployVocabulary'

/**
 * Where this deployment got to, drawn as the state machine it actually is.
 *
 * `DeploymentStatus` is one value, and one value cannot answer the question somebody has
 * when a deploy is slow: is it waiting for a machine, or is it compiling, or has it
 * finished compiling and got stuck going live. Those are three different problems with
 * three different answers, so the path is drawn whole and the current position is marked
 * on it.
 *
 * Where a step is not knowable, it is not claimed. A failed deployment does not record
 * which step it failed at, so how far it got is inferred from the two facts that are
 * recorded - a node id means it was assigned, a start time means work began - and
 * everything past that is drawn as "did not get here" rather than as a guess dressed up
 * with a tick.
 */
type StepState = 'done' | 'active' | 'stopped' | 'pending'

interface Step {
  rank: number
  label: string
  detail: string
  at: string | null
  state: StepState
}

/** The order the state machine runs in. The outcome is rank 4, whatever it turns out to be. */
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
    <Card title="Progress" description={statusSentence(deployment.status)}>
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
      label: 'Queued',
      detail: 'Accepted and written down, waiting for a worker to pick it up.',
      at: deployment.queuedAt,
      state: state(0),
    },
    {
      rank: 1,
      label: 'Handed to a node',
      detail:
        deployment.nodeId === null
          ? 'Waiting for a node with room for it.'
          : 'A node was chosen and the work was sent to it.',
      at: null,
      state: state(1),
    },
  ]

  if (!app && !restored) {
    steps.push({
      rank: 2,
      label: 'Building',
      detail: 'Cloning, installing and compiling on the node, in a throwaway container.',
      at: deployment.startedAt,
      state: state(2),
    })
  }

  steps.push({
    rank: 3,
    label: 'Publishing',
    detail: app
      ? 'Starting a container from the new image and retiring the old one.'
      : restored
        ? 'Pointing the live symlink back at the release that is already on disk.'
        : 'Swapping the live symlink onto the new release directory. No downtime, and not cancellable.',
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

/**
 * The last rank the record proves it got to.
 *
 * A superseded deployment never left the queue by definition. Otherwise: a node id means
 * it was assigned, and a start time means the node began work on it.
 */
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
    return deployment.current ? 'Live' : 'Succeeded'
  }
  if (STOPPED_EARLY.includes(deployment.status)) {
    return statusLabel(deployment.status)
  }
  return 'Finished'
}

function outcomeDetail(deployment: DeploymentSummary): string {
  if (deployment.errorMessage) {
    return deployment.errorMessage
  }
  if (deployment.status === 'SUCCEEDED') {
    return deployment.current
      ? 'This is what visitors are being served.'
      : 'It was published and something newer has taken over since. It can be rolled back to.'
  }
  if (STOPPED_EARLY.includes(deployment.status)) {
    return statusSentence(deployment.status)
  }
  return 'Not finished yet.'
}
