import {t} from '@/i18n'
import {Badge, Card, ErrorState, Spinner, Tabs, formatBytes} from '@/shell'
import type {TabItem} from '@/shell'

import {MetricChart} from './MetricChart'
import type {ChartBand} from './MetricChart'
import {MetricSummary} from './MetricSummary'
import {formatCpu, formatRate, ratesOf, resolutionLabel} from './metricFormat'
import type {MetricSeries} from './statsTypes'
import {useMetricStream} from './useMetricStream'
import type {MetricRange} from './useMetricStream'

/** The palette's own variables, so a chart cannot drift from the badges beside it. */
const ACCENT = 'var(--color-accent-500)'
const PEAK = 'var(--color-degraded)'
const OUT = 'var(--color-running)'
const LIMIT = 'var(--color-failed)'

/**
 * The charts, shared by the service page and the node page.
 *
 * The two differ in which subject they watch and who is allowed to watch it, and nothing
 * else - both read the same `MetricSeries`, from endpoints with the same three shapes
 * under a different prefix. Writing this twice would be two SSE lifecycles with one bug
 * each, which is exactly the argument `MetricStream` makes on the server side.
 *
 * Five charts rather than one with five lines. CPU is millicores, memory is bytes, disk
 * throughput is bytes per second and network is bytes per second in two directions;
 * putting them on one axis makes four of them invisible.
 *
 * The resolution is named under the heading. A series can answer from raw samples, hourly
 * buckets or daily buckets depending on how far back the window reaches, and a chart that
 * silently changes what a point means as it is zoomed is one nobody should trust.
 */
export function MetricDashboard({
  basePath,
  initialSeries,
  liveWindowSeconds,
}: {
  /** `/services/{id}/metrics` or `/admin/metrics/nodes/{id}`. */
  basePath: string
  initialSeries: MetricSeries
  liveWindowSeconds: number
}) {
  const feed = useMetricStream(basePath, initialSeries, liveWindowSeconds)
  const {series} = feed
  const points = series.points
  const timestamps = points.map((point) => point.at)

  const rxRate = ratesOf(points, series.source, (point) => point.networkRxBytes)
  const txRate = ratesOf(points, series.source, (point) => point.networkTxBytes)
  const readRate = ratesOf(points, series.source, (point) => point.diskReadBytes)
  const writeRate = ratesOf(points, series.source, (point) => point.diskWriteBytes)

  const memoryBands: ChartBand[] = [
    {label: t('stats.chart.bandUsed'), values: points.map((point) => point.memoryBytes), colour: ACCENT},
    {label: t('stats.chart.bandPeak'), values: points.map((point) => point.memoryBytesMax), colour: PEAK},
  ]
  const limits = points.map((point) => point.memoryLimitBytes ?? 0)
  if (limits.some((value) => value > 0)) {
    memoryBands.push({label: t('stats.chart.bandLimit'), values: limits, colour: LIMIT, reference: true})
  }

  const tabs: TabItem[] = [
    {value: 'live', label: t('stats.dashboard.rangeLive')},
    {value: '1h', label: t('stats.dashboard.range1h')},
    {value: '6h', label: t('stats.dashboard.range6h')},
    {value: '24h', label: t('stats.dashboard.range24h')},
    {value: '7d', label: t('stats.dashboard.range7d')},
    {value: '30d', label: t('stats.dashboard.range30d')},
  ]

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <Tabs
          label={t('stats.dashboard.rangeLabel')}
          items={tabs}
          value={feed.range}
          onSelect={(value) => feed.setRange(value as MetricRange)}
          className="min-w-0 flex-1"
        />
        {feed.range === 'live' ? (
          <Badge tone={feed.streaming ? 'running' : 'degraded'} dot pulse={!feed.streaming}>
            {feed.streaming ? t('stats.dashboard.statusLive') : t('stats.dashboard.statusConnecting')}
          </Badge>
        ) : null}
        {feed.loading ? <Spinner /> : null}
      </div>

      {feed.error ? (
        <ErrorState
          title={t('stats.dashboard.errorTitle')}
          description={feed.error}
          onRetry={feed.retry}
        />
      ) : null}

      <MetricSummary series={series} />

      <Card
        title={t('stats.dashboard.overTime')}
        description={t('stats.dashboard.overTimeDesc', {
          count: points.length.toLocaleString(),
          resolution: resolutionLabel(series.source),
        })}
      >
        <div className="flex flex-col gap-5">
          <MetricChart
            title={t('stats.chart.cpu')}
            timestamps={timestamps}
            format={formatCpu}
            bands={[
              {
                label: t('stats.chart.bandAverage'),
                values: points.map((point) => point.cpuMillicores),
                colour: ACCENT,
              },
              {
                label: t('stats.chart.bandPeak'),
                values: points.map((point) => point.cpuMillicoresMax),
                colour: PEAK,
              },
            ]}
          />

          <MetricChart
            title={t('stats.chart.memory')}
            timestamps={timestamps}
            format={formatBytes}
            bands={memoryBands}
          />

          <MetricChart
            title={t('stats.chart.diskUsed')}
            timestamps={timestamps}
            format={formatBytes}
            bands={[
              {label: t('stats.chart.bandOnDisk'), values: points.map((point) => point.diskBytes), colour: ACCENT},
            ]}
          />

          <MetricChart
            title={t('stats.chart.diskThroughput')}
            timestamps={timestamps}
            format={formatRate}
            bands={[
              {label: t('stats.chart.bandRead'), values: readRate, colour: ACCENT},
              {label: t('stats.chart.bandWritten'), values: writeRate, colour: OUT},
            ]}
          />

          <MetricChart
            title={t('stats.chart.network')}
            timestamps={timestamps}
            format={formatRate}
            bands={[
              {label: t('stats.chart.bandIn'), values: rxRate, colour: ACCENT},
              {label: t('stats.chart.bandOut'), values: txRate, colour: OUT},
            ]}
          />
        </div>
      </Card>
    </div>
  )
}

