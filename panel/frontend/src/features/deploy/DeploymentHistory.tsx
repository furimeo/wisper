import {useEffect, useState} from 'react'

import {DataList, EmptyState, Icon, RelativeTime} from '@/shell'
import type {DataListColumn, SwipeAction} from '@/shell'

import {DeploymentStatusBadge} from './DeploymentStatusBadge'
import {cancelDeployment} from './cancelDeployment'
import type {DeploymentSummary} from './deployTypes'
import {cancellable, describe, durationOf, formatDuration, rollbackTarget} from './deployVocabulary'
import {rollbackRelease} from './rollbackRelease'

/** How often the elapsed time of a running build is redrawn. */
const TICK_MS = 1000

/**
 * The history: one row per deployment, newest first.
 *
 * A row on a phone is the three things somebody is scanning for - which number, what it
 * was, and where it got to - with cancel and roll back behind the swipe and the overflow
 * button. The desktop table adds who triggered it and how long it took, which are worth a
 * column when there is room and are not worth two lines of a 375px row when there is not.
 *
 * The clock only runs while something is still building. A page of forty finished
 * deployments re-rendering once a second to show forty unchanging durations is a phone
 * warming up in somebody's hand for nothing.
 */
export function DeploymentHistory({
  serviceId,
  deployments,
  writable,
}: {
  serviceId: string
  deployments: DeploymentSummary[]
  writable: boolean
}) {
  const running = deployments.some((one) => durationOf(one) === null && one.startedAt !== null)
  const now = useTickingClock(running)

  const columns: Array<DataListColumn<DeploymentSummary>> = [
    {
      key: 'sequence',
      header: '#',
      cell: (one) => <span className="tabular-nums">#{one.sequence}</span>,
      className: 'w-16',
    },
    {
      key: 'status',
      header: 'Status',
      cell: (one) => <DeploymentStatusBadge deployment={one} />,
    },
    {
      key: 'what',
      header: 'What was deployed',
      cell: (one) => <span className="line-clamp-2">{describe(one)}</span>,
    },
    {
      key: 'queued',
      header: 'Started',
      cell: (one) => <RelativeTime at={one.queuedAt} />,
    },
    {
      key: 'duration',
      header: 'Took',
      align: 'right',
      cell: (one) => (
        <span className="tabular-nums">{formatDuration(durationOf(one, now))}</span>
      ),
    },
  ]

  return (
    <DataList
      label="deployments"
      items={deployments}
      keyOf={(one) => one.id}
      columns={columns}
      href={(one) => `/services/${serviceId}/deployments/${one.id}`}
      primary={(one) => (
        <span className="flex items-center gap-2">
          <span className="tabular-nums">#{one.sequence}</span>
          <DeploymentStatusBadge deployment={one} />
        </span>
      )}
      secondary={(one) => describe(one)}
      trailing={(one) => (
        <span className="flex flex-col items-end text-xs text-ink-500 dark:text-ink-400">
          <RelativeTime at={one.queuedAt} />
          <span className="tabular-nums">{formatDuration(durationOf(one, now))}</span>
        </span>
      )}
      actions={writable ? (one) => actionsFor(serviceId, one) : undefined}
      empty={
        <EmptyState
          icon={<Icon name="jobs" />}
          title="Nothing has been deployed yet"
          description={
            'Every deployment of this service will be listed here with its build log, ' +
            'and any successful one can be rolled back to in a tap. Start one above.'
          }
        />
      }
    />
  )
}

/** Cancel while it is moving, roll back once it is not. Never both. */
function actionsFor(serviceId: string, deployment: DeploymentSummary): SwipeAction[] {
  const actions: SwipeAction[] = []
  if (cancellable(deployment)) {
    actions.push({
      label: 'Cancel this build',
      tone: 'danger',
      icon: <Icon name="close" className="size-4" />,
      onSelect: () => void cancelDeployment(serviceId, deployment),
    })
  }
  if (rollbackTarget(deployment)) {
    actions.push({
      label: `Roll back to #${deployment.sequence}`,
      icon: <Icon name="chevronLeft" className="size-4" />,
      onSelect: () => void rollbackRelease(serviceId, deployment),
    })
  }
  return actions
}

/** `Date.now()`, redrawn once a second, and only while something is actually running. */
function useTickingClock(running: boolean): number | undefined {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (!running) {
      return
    }
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), TICK_MS)
    return () => window.clearInterval(timer)
  }, [running])

  return running ? now : undefined
}
