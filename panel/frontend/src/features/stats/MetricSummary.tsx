import type {ReactNode} from 'react'

import {t} from '@/i18n'
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
        {t('stats.summary.empty')}
      </p>
    )
  }

  const seconds = intervalSeconds(series.points, series.points.length - 1, series.source)
  const percent = memoryPercent(latest)

  return (
    <dl className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
      <Tile label={t('stats.summary.cpu')} value={formatCpu(latest.cpuMillicores)}>
        {t('stats.summary.cpuPeak', {peak: formatCpu(latest.cpuMillicoresMax)})}
      </Tile>

      <Tile label={t('stats.summary.memory')} value={<ByteSize bytes={latest.memoryBytes} />}>
        {latest.memoryLimitBytes === null || latest.memoryLimitBytes <= 0
          ? t('stats.summary.memoryNoLimit')
          : t('stats.summary.memoryWithLimit', {percent: percent ?? 0, limit: formatBytes(latest.memoryLimitBytes)})}
      </Tile>

      <Tile label={t('stats.summary.disk')} value={<ByteSize bytes={latest.diskBytes} />}>
        {t('stats.summary.diskRead', {rate: formatBytes(latest.diskReadBytes / seconds)})}
      </Tile>

      <Tile label={t('stats.summary.netIn')} value={`${formatBytes(latest.networkRxBytes / seconds)}/s`}>
        {t('stats.summary.netInPoint', {amount: formatBytes(latest.networkRxBytes)})}
      </Tile>

      <Tile label={t('stats.summary.netOut')} value={`${formatBytes(latest.networkTxBytes / seconds)}/s`}>
        {latest.restartCount > 0 ? (
          <Badge tone="degraded">
            {t('stats.summary.restarts', {count: latest.restartCount})}
          </Badge>
        ) : (
          t('stats.summary.noRestarts')
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
