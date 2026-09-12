import {useEffect, useState} from 'react'

import {t} from '@/i18n'
import {DataList, EmptyState, Icon, RelativeTime} from '@/shell'
import type {DataListColumn, SwipeAction} from '@/shell'

import {DeploymentStatusBadge} from './DeploymentStatusBadge'
import {cancelDeployment} from './cancelDeployment'
import type {DeploymentSummary} from './deployTypes'
import {cancellable, describe, durationOf, formatDuration, rollbackTarget} from './deployVocabulary'
import {rollbackRelease} from './rollbackRelease'

const TICK_MS = 1000

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
      header: t('deploy.history.col_sequence'),
      cell: (one) => <span className="tabular-nums">#{one.sequence}</span>,
      className: 'w-16',
    },
    {
      key: 'status',
      header: t('deploy.history.col_status'),
      cell: (one) => <DeploymentStatusBadge deployment={one} />,
    },
    {
      key: 'what',
      header: t('deploy.history.col_what'),
      cell: (one) => <span className="line-clamp-2">{describe(one)}</span>,
    },
    {
      key: 'queued',
      header: t('deploy.history.col_started'),
      cell: (one) => <RelativeTime at={one.queuedAt} />,
    },
    {
      key: 'duration',
      header: t('deploy.history.col_took'),
      align: 'right',
      cell: (one) => (
        <span className="tabular-nums">{formatDuration(durationOf(one, now))}</span>
      ),
    },
  ]

  return (
    <DataList
      label={t('deploy.history.label')}
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
          title={t('deploy.history.empty_title')}
          description={t('deploy.history.empty_description')}
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
      label: t('deploy.history.cancel_action'),
      tone: 'danger',
      icon: <Icon name="close" className="size-4" />,
      onSelect: () => void cancelDeployment(serviceId, deployment),
    })
  }
  if (rollbackTarget(deployment)) {
    actions.push({
      label: t('deploy.history.rollback_action', {sequence: deployment.sequence}),
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
