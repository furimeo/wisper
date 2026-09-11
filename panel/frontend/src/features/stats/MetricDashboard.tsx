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

const RANGES: Array<{value: MetricRange; label: string}> = [
  {value: 'live', label: 'Live'},
  {value: '1h', label: '1 hour'},
  {value: '6h', label: '6 hours'},
  {value: '24h', label: '24 hours'},
  {value: '7d', label: '7 days'},
  {value: '30d', label: '30 days'},
]

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
    {label: 'Used', values: points.map((point) => point.memoryBytes), colour: ACCENT},
    {label: 'Peak', values: points.map((point) => point.memoryBytesMax), colour: PEAK},
  ]
  const limits = points.map((point) => point.memoryLimitBytes ?? 0)
  if (limits.some((value) => value > 0)) {
    memoryBands.push({label: 'Limit', values: limits, colour: LIMIT, reference: true})
  }

  const tabs: TabItem[] = RANGES.map((entry) => ({value: entry.value, label: entry.label}))

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <Tabs
          label="Chart range"
          items={tabs}
          value={feed.range}
          onSelect={(value) => feed.setRange(value as MetricRange)}
          className="min-w-0 flex-1"
        />
        {feed.range === 'live' ? (
          <Badge tone={feed.streaming ? 'running' : 'degraded'} dot pulse={!feed.streaming}>
            {feed.streaming ? 'Live' : 'Connecting'}
          </Badge>
        ) : null}
        {feed.loading ? <Spinner /> : null}
      </div>

      {feed.error ? (
        <ErrorState
          title="That range could not be read"
          description={feed.error}
          onRetry={feed.retry}
        />
      ) : null}

      <MetricSummary series={series} />

      <Card
        title="Over time"
        description={`${points.length.toLocaleString()} points, ${resolutionLabel(series.source)}.`}
      >
        <div className="flex flex-col gap-5">
          <MetricChart
            title="CPU"
            timestamps={timestamps}
            format={formatCpu}
            bands={[
              {
                label: 'Average',
                values: points.map((point) => point.cpuMillicores),
                colour: ACCENT,
              },
              {
                label: 'Peak',
                values: points.map((point) => point.cpuMillicoresMax),
                colour: PEAK,
              },
            ]}
          />

          <MetricChart
            title="Memory"
            timestamps={timestamps}
            format={formatBytes}
            bands={memoryBands}
          />

          <MetricChart
            title="Disk used"
            timestamps={timestamps}
            format={formatBytes}
            bands={[
              {label: 'On disk', values: points.map((point) => point.diskBytes), colour: ACCENT},
            ]}
          />

          <MetricChart
            title="Disk throughput"
            timestamps={timestamps}
            format={formatRate}
            bands={[
              {label: 'Read', values: readRate, colour: ACCENT},
              {label: 'Written', values: writeRate, colour: OUT},
            ]}
          />

          <MetricChart
            title="Network"
            timestamps={timestamps}
            format={formatRate}
            bands={[
              {label: 'In', values: rxRate, colour: ACCENT},
              {label: 'Out', values: txRate, colour: OUT},
            ]}
          />
        </div>
      </Card>
    </div>
  )
}
