import type {ReactNode} from 'react'

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
            title="No nodes yet"
            description="A node is one machine running sasayaki. Add one and the panel gives you a
              bootstrap token and a three-line install command to paste into its shell."
          />
        )
      }
      primary={(node) => (
        <span className="flex items-center gap-2">
          <span className="truncate">{node.name}</span>
          {node.lessIsolated || node.quotaAdvisory ? (
            <span className="text-failed" title="Weaker isolation than the platform claims">
              <Icon name="shield" label="Weaker isolation" className="size-4" />
            </span>
          ) : null}
        </span>
      )}
      secondary={(node) => <span className="line-clamp-2">{nodeSentence(node)}</span>}
      trailing={(node) => <RowTrailing node={node} />}
      columns={[
        {
          key: 'name',
          header: 'Node',
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
          header: 'State',
          cell: (node) => (
            <div className="flex flex-col items-start gap-1">
              <NodeStateBadges node={node} />
              <NodeIsolationBadges node={node} />
            </div>
          ),
        },
        {
          key: 'workloads',
          header: 'Workloads',
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
          header: 'Fullest meter',
          align: 'right',
          cell: (node) => <ShareCell node={node} />,
        },
        {
          key: 'tags',
          header: 'Tags',
          cell: (node) =>
            node.tags.length === 0 ? (
              <span className="text-ink-400">—</span>
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
          header: 'Agent',
          cell: (node) => (
            <span className="font-mono text-xs">
              {node.agentVersion ?? '—'}
              {node.needsUpgrade ? (
                <span className="ml-1 font-sans text-degraded">update</span>
              ) : null}
            </span>
          ),
        },
        {
          key: 'heartbeat',
          header: 'Heartbeat',
          align: 'right',
          cell: (node) => (
            <RelativeTime at={node.lastHeartbeatAt} fallback="never" className="text-xs" />
          ),
        },
      ]}
    />
  )
}

/** The right-hand side of a phone row: state, then how full the machine is. */
function RowTrailing({node}: {node: NodeSummary}) {
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
          {Math.round(share * 100)}% full
        </span>
      )}
    </div>
  )
}

/** The tightest of CPU, memory and disk, with the CPU figure for context. */
function ShareCell({node}: {node: NodeSummary}) {
  const share = tightestShare(node)
  if (share === null) {
    return <span className="text-ink-400">not reported</span>
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
          of {formatMillicores(node.cpuMillicoresCapacity)}
        </span>
      )}
    </span>
  )
}
