import {t} from '@/i18n'
import {formatBytes} from '@/shell'

import type {MetricPoint, MetricSource} from './statsTypes'

/**
 * Turning the numbers a node reports into the numbers a person reads.
 *
 * Two of these are not cosmetic. CPU arrives in millicores, which is the unit the limit
 * is set in and not a unit anybody thinks in past about half a core - so it is shown as
 * cores once it is worth it. And the network and disk counters are *totals over the
 * point's interval*, which is a different quantity at every resolution: the same
 * `networkRxBytes` means fifteen seconds of traffic in a raw sample and a whole day of it
 * in a daily bucket. Drawing those on one axis without dividing by the interval produces
 * a chart where zooming out makes the traffic go up.
 */

/** Nominal width of one point, in seconds, per the resolution that produced it. */
const BUCKET_SECONDS: Record<MetricSource, number> = {
  RAW: 15,
  HOUR: 3600,
  DAY: 86400,
}

/** What the axis should call itself, so a chart never implies a resolution it lacks. */
export function resolutionLabel(source: MetricSource): string {
  switch (source) {
    case 'RAW':
      return t('stats.resolution.raw')
    case 'HOUR':
      return t('stats.resolution.hour')
    case 'DAY':
      return t('stats.resolution.day')
  }
}

/** Millicores as a person reads them: `350 m` under a core, `2.4 cores` above. */
export function formatCpu(millicores: number): string {
  if (millicores < 1000) {
    return `${Math.round(millicores)} m`
  }
  const cores = millicores / 1000
  const formatted = cores < 10 ? cores.toFixed(2) : cores.toFixed(1)
  return t('stats.format.cores', {cores: formatted})
}

/** A throughput, from a total and the seconds it covers. */
export function formatRate(bytesPerSecond: number): string {
  return `${formatBytes(bytesPerSecond)}/s`
}

/**
 * How many seconds one point covers.
 *
 * Measured from the gap to the previous point where there is one, because a node's real
 * cadence is not exactly the nominal fifteen seconds and a gap in the data is a real gap.
 * The first point has nothing to measure against and falls back to the nominal width.
 */
export function intervalSeconds(
  points: MetricPoint[],
  index: number,
  source: MetricSource,
): number {
  const nominal = BUCKET_SECONDS[source]
  if (index <= 0) {
    return nominal
  }
  const current = points[index]
  const previous = points[index - 1]
  if (!current || !previous) {
    return nominal
  }
  const gap = (Date.parse(current.at) - Date.parse(previous.at)) / 1000
  // A gap of an hour in raw samples is a node that was offline, not an hour of traffic in
  // one reading. Clamping keeps one such point from flattening the whole chart.
  return gap > 0 && gap < nominal * 4 ? gap : nominal
}

/** The per-second rate of a counter field, across a whole series. */
export function ratesOf(
  points: MetricPoint[],
  source: MetricSource,
  pick: (point: MetricPoint) => number,
): number[] {
  return points.map((point, index) => pick(point) / intervalSeconds(points, index, source))
}

/** The clock time of a point, at the precision the resolution justifies. */
export function pointLabel(at: string, source: MetricSource): string {
  const when = new Date(at)
  if (source === 'DAY') {
    return when.toLocaleDateString(undefined, {month: 'short', day: 'numeric'})
  }
  if (source === 'HOUR') {
    return when.toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  }
  return when.toLocaleTimeString(undefined, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

/** How full the memory limit was, in percent, or null when there is no limit. */
export function memoryPercent(point: MetricPoint): number | null {
  if (point.memoryLimitBytes === null || point.memoryLimitBytes <= 0) {
    return null
  }
  return Math.min(100, Math.round((point.memoryBytesMax * 100) / point.memoryLimitBytes))
}
