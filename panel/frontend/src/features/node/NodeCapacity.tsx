import {cx, formatBytes} from '@/shell'

import type {NodeSummary} from './nodeTypes'

/**
 * What a machine has left, drawn.
 *
 * Three meters and not a table: the question an operator opens the fleet with is "can
 * this take another service", and that is answered by how full the bars are, at a glance,
 * from across a room or on a 375px screen.
 *
 * A null capacity is not zero. A node that has never reported has no capacity figures at
 * all, and drawing an empty bar for it would say "completely free" about a machine the
 * panel has never spoken to. Those render as a sentence instead.
 *
 * Headroom is why the amber threshold is 80% rather than 95%: `placement.ChooseNode`
 * refuses to schedule past the reserve, so a node at 85% is already effectively full and
 * an operator should see that before a deployment fails to place.
 */
const WARN_SHARE = 0.8

const FULL_SHARE = 0.95

export interface NodeMeter {
  label: string
  used: number | null
  capacity: number | null
  format: (value: number) => string
}

export function NodeCapacity({node, className}: {node: NodeSummary; className?: string}) {
  const meters: NodeMeter[] = [
    {
      label: 'CPU',
      used: node.cpuMillicoresUsed,
      capacity: node.cpuMillicoresCapacity,
      format: formatMillicores,
    },
    {
      label: 'Memory',
      used: node.memoryBytesUsed,
      capacity: node.memoryBytesCapacity,
      format: formatBytes,
    },
    {
      label: 'Disk',
      used: node.diskBytesUsed,
      capacity: node.diskBytesCapacity,
      format: formatBytes,
    },
  ]

  const reported = meters.some((meter) => meter.capacity !== null && meter.capacity > 0)
  if (!reported) {
    return (
      <p className={cx('text-sm text-ink-500 dark:text-ink-400', className)}>
        No capacity reported. The node sends its CPU, memory and disk when it connects, so
        this fills in as soon as it does.
      </p>
    )
  }

  return (
    <dl className={cx('flex flex-col gap-3', className)}>
      {meters.map((meter) => (
        <CapacityMeter key={meter.label} meter={meter} />
      ))}
    </dl>
  )
}

/** One bar, its numbers, and nothing else. */
export function CapacityMeter({meter}: {meter: NodeMeter}) {
  const {label, used, capacity, format} = meter
  const known = capacity !== null && capacity > 0 && used !== null
  const share = known ? Math.min(1, Math.max(0, used / capacity)) : 0
  const percent = Math.round(share * 100)

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-baseline justify-between gap-3">
        <dt className="text-sm font-medium text-ink-700 dark:text-ink-300">{label}</dt>
        <dd
          className={cx(
            'text-sm tabular-nums',
            share >= FULL_SHARE
              ? 'text-failed'
              : share >= WARN_SHARE
                ? 'text-degraded'
                : 'text-ink-500 dark:text-ink-400',
          )}
        >
          {known ? `${format(used)} of ${format(capacity)}` : 'not reported'}
        </dd>
      </div>
      <div
        className="h-1.5 w-full overflow-hidden rounded-full bg-ink-200 dark:bg-ink-800"
        role="img"
        aria-label={
          known ? `${label}: ${percent}% used` : `${label}: the node has not reported this`
        }
      >
        <div
          className={cx(
            'h-full',
            share >= FULL_SHARE
              ? 'bg-failed'
              : share >= WARN_SHARE
                ? 'bg-degraded'
                : 'bg-accent-500',
          )}
          style={{width: `${percent}%`}}
        />
      </div>
    </div>
  )
}

/**
 * The share of the tightest resource, for a list row that has space for one number.
 *
 * The tightest and not the average: a node with plenty of RAM and no disk left cannot
 * take a workload, and an average would report it as half empty.
 */
export function tightestShare(node: NodeSummary): number | null {
  const shares = [
    ratio(node.cpuMillicoresUsed, node.cpuMillicoresCapacity),
    ratio(node.memoryBytesUsed, node.memoryBytesCapacity),
    ratio(node.diskBytesUsed, node.diskBytesCapacity),
  ].filter((value): value is number => value !== null)
  return shares.length === 0 ? null : Math.max(...shares)
}

function ratio(used: number | null, capacity: number | null): number | null {
  if (used === null || capacity === null || capacity <= 0) {
    return null
  }
  return Math.min(1, Math.max(0, used / capacity))
}

/** 1500 millicores is "1.5 cores", which is the unit an operator thinks in. */
export function formatMillicores(millicores: number): string {
  if (millicores < 1000) {
    return `${Math.round(millicores)}m`
  }
  const cores = millicores / 1000
  return `${cores < 10 ? cores.toFixed(1) : Math.round(cores)} cores`
}
