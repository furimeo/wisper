import type {ReactNode} from 'react'

import {Badge, ByteSize, cx, formatBytes} from '@/shell'

import {formatCpu, intervalSeconds, memoryPercent} from './metricFormat'
import type {MetricSeries} from './statsTypes'

/**
 * The last reading, as five numbers somebody can act on.
 *
 * A chart answers "what has this been doing"; this answers "what is it doing", which is
 * the question somebody opens the page with and the only one that fits above the fold on
 * a phone. Everything here comes from the most recent point rather than from an average,
 * because an average of the last six hours is not what "now" means.
 *
 * Memory carries its limit, and that pair is the whole point: 400 MB is fine against a
 * gigabyte and is an OOM kill about to happen against 420 MB. Where there is no limit the
 * percentage is left out rather than invented.
 */
export function MetricSummary({series}: {series: MetricSeries}) {
  const latest = series.points.at(-1)

  if (!latest) {
    return (
      <p className="rounded-xl border border-dashed border-ink-300 px-4 py-6 text-center text-sm text-ink-500 dark:border-ink-700 dark:text-ink-400">
        Nothing has been measured in this window yet. A node pushes readings while a
        workload is running, so a service that has never started has nothing here.
      </p>
    )
  }

  const seconds = intervalSeconds(series.points, series.points.length - 1, series.source)
  const percent = memoryPercent(latest)

  return (
    <dl className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
      <Tile label="CPU" value={formatCpu(latest.cpuMillicores)}>
        peak {formatCpu(latest.cpuMillicoresMax)}
      </Tile>

      <Tile label="Memory" value={<ByteSize bytes={latest.memoryBytes} />}>
        {latest.memoryLimitBytes === null || latest.memoryLimitBytes <= 0
          ? 'no limit set'
          : `${percent}% of ${formatBytes(latest.memoryLimitBytes)}`}
      </Tile>

      <Tile label="Disk" value={<ByteSize bytes={latest.diskBytes} />}>
        {formatBytes(latest.diskReadBytes / seconds)}/s read
      </Tile>

      <Tile label="Network in" value={`${formatBytes(latest.networkRxBytes / seconds)}/s`}>
        {formatBytes(latest.networkRxBytes)} this point
      </Tile>

      <Tile label="Network out" value={`${formatBytes(latest.networkTxBytes / seconds)}/s`}>
        {latest.restartCount > 0 ? (
          <Badge tone="degraded">
            {latest.restartCount} restart{latest.restartCount === 1 ? '' : 's'}
          </Badge>
        ) : (
          'no restarts'
        )}
      </Tile>
    </dl>
  )
}

function Tile({
  label,
  value,
  children,
  className,
}: {
  label: string
  value: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <div
      className={cx(
        'rounded-xl border border-ink-200 bg-white px-3 py-2.5',
        'dark:border-ink-800 dark:bg-ink-900',
        className,
      )}
    >
      <dt className="text-xs font-medium uppercase tracking-wide text-ink-500 dark:text-ink-400">
        {label}
      </dt>
      <dd className="mt-0.5 text-base font-semibold tabular-nums text-ink-900 dark:text-ink-100">
        {value}
      </dd>
      <dd className="mt-0.5 text-xs text-ink-500 dark:text-ink-400">{children}</dd>
    </div>
  )
}
