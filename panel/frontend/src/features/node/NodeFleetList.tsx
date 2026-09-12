import type {ReactNode} from 'react'

import {useI18n} from '@/i18n'
import {Badge, DataList, EmptyState, Icon, RelativeTime} from '@/shell'

import {formatMillicores, tightestShare} from './NodeCapacity'
import {NodeIsolationBadges, NodeStateBadges} from './NodeStateBadges'
import type {NodeSummary} from './nodeTypes'
import {connectionLabel, lifecycleLabel, nodeSentence, shortId} from './nodeVocabulary'

/**
 * The fleet, as a table on a desktop and one row per machine on a phone.
 *
 * The phone row carries three things and no more: the name, the sentence that says whether
 * the machine is all right, and the fullest of its three meters. An operator standing
 * somewhere holding a telephone is asking "is anything wrong" and "can this take more
 * work", and those two answers are what fits.
 *
 * The isolation badges are on the row rather than only on the detail page. A machine
 * without gVisor should be visible from the list, because the decision it affects - what
 * to schedule where - is made from the list.
 */
export function NodeFleetList({
  nodes,
  label = 'nodes',
  empty,
}: {
  nodes: NodeSummary[]
  label?: string
  empty?: ReactNode
}) {
  const {t} = useI18n()

  return (
    <DataList
      items={nodes}
      keyOf={(node) => node.id}
      label={label}
      href={(node) => `/admin/nodes/${node.id}`}
      empty={
        empty ?? (
          <EmptyState
            icon={<Icon name="node" />}
            title={t('node.list.empty.title')}
            description={t('node.list.empty.description')}
          />
        )
      }
      primary={(node) => (
        <span className="flex items-center gap-2">
          <span className="truncate">{node.name}</span>
          {node.lessIsolated || node.quotaAdvisory ? (
            <span className="text-failed" title={t('node.fleet.isolationWarning')}>
              <Icon name="shield" label={t('node.fleet.isolationLabel')} className="size-4" />
            </span>
          ) : null}
        </span>
      )}
      secondary={(node) => <span className="line-clamp-2">{nodeSentence(node)}</span>}
      trailing={(node) => <RowTrailing node={node} />}
      columns={[
        {
          key: 'name',
          header: t('node.fleet.col.node'),
          cell: (node) => (
            <div className="flex flex-col gap-0.5">
              <span>{node.name}</span>
              <span className="font-mono text-xs text-ink-500 dark:text-ink-400">
                {node.publicAddress ?? shortId(node.id)}
              </span>
            </div>
          ),
        },
        {
          key: 'state',
          header: t('node.fleet.col.state'),
          cell: (node) => (
            <div className="flex flex-col items-start gap-1">
              <NodeStateBadges node={node} />
              <NodeIsolationBadges node={node} />
            </div>
          ),
        },
        {
          key: 'workloads',
          header: t('node.fleet.col.workloads'),
          align: 'right',
          cell: (node) => (
            <span className="tabular-nums">
              {node.runningWorkloadCount}
              <span className="text-ink-500 dark:text-ink-400"> / {node.workloadCount}</span>
            </span>
          ),
        },
        {
          key: 'capacity',
          header: t('node.fleet.col.capacity'),
          align: 'right',
          cell: (node) => <ShareCell node={node} />,
        },
        {
          key: 'tags',
          header: t('node.fleet.col.tags'),
          cell: (node) =>
            node.tags.length === 0 ? (
              <span className="text-ink-400">-</span>
            ) : (
              <span className="flex flex-wrap gap-1">
                {node.tags.map((tag) => (
                  <Badge key={tag}>{tag}</Badge>
                ))}
              </span>
            ),
        },
        {
          key: 'version',
          header: t('node.fleet.col.agent'),
          cell: (node) => (
            <span className="font-mono text-xs">
              {node.agentVersion ?? '-'}
              {node.needsUpgrade ? (
                <span className="ml-1 font-sans text-degraded">{t('node.fleet.updateBadge')}</span>
              ) : null}
            </span>
          ),
        },
        {
          key: 'heartbeat',
          header: t('node.fleet.col.heartbeat'),
          align: 'right',
          cell: (node) => (
            <RelativeTime at={node.lastHeartbeatAt} fallback={t('node.fleet.never')} className="text-xs" />
          ),
        },
      ]}
    />
  )
}

/** The right-hand side of a phone row: state, then how full the machine is. */
function RowTrailing({node}: {node: NodeSummary}) {
  const {t} = useI18n()
  const share = tightestShare(node)
  return (
    <div className="flex flex-col items-end gap-1">
      <Badge
        tone={
          node.lifecycle === 'SUSPENDED'
            ? 'failed'
            : node.lifecycle === 'DRAINING'
              ? 'degraded'
              : node.connected
                ? 'running'
                : 'neutral'
        }
        dot
      >
        {node.lifecycle === 'ENROLLED'
          ? connectionLabel(node.connectionState)
          : lifecycleLabel(node.lifecycle)}
      </Badge>
      {share === null ? null : (
        <span className="text-xs tabular-nums text-ink-500 dark:text-ink-400">
          {t('node.fleet.percentFull', {percent: Math.round(share * 100)})}
        </span>
      )}
    </div>
  )
}

/** The tightest of CPU, memory and disk, with the CPU figure for context. */
function ShareCell({node}: {node: NodeSummary}) {
  const {t} = useI18n()
  const share = tightestShare(node)
  if (share === null) {
    return <span className="text-ink-400">{t('node.fleet.notReported')}</span>
  }
  return (
    <span
      className={
        share >= 0.95 ? 'text-failed tabular-nums' : share >= 0.8 ? 'text-degraded tabular-nums' : 'tabular-nums'
      }
    >
      {Math.round(share * 100)}%
      {node.cpuMillicoresCapacity === null ? null : (
        <span className="ml-1 text-xs text-ink-500 dark:text-ink-400">
          {t('node.fleet.ofCapacity', {capacity: formatMillicores(node.cpuMillicoresCapacity)})}
        </span>
      )}
    </span>
  )
}
