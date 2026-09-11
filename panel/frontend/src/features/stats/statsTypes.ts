/**
 * The `stats` package's records, as they arrive on a page or over a stream.
 *
 * One interface per Java record in `lhqm.furimeo.wisper.stats`, components in declaration
 * order, with the derived accessors Jackson serialises marked. `docs/contracts/pages.md`
 * §4 and §5 are the source; `MetricSeries.isEmpty()` becomes `empty`, and nothing else on
 * these records is bean-named, so `latest()`, `peakCpuMillicores()` and
 * `memoryPercentOfLimit()` are computed here instead.
 */

/** `stats.MetricSource`. Which table answered, and therefore how wide a point is. */
export type MetricSource = 'RAW' | 'HOUR' | 'DAY'

/** `proto.LogSource`, as `ServiceLogController` sends it: the enum's own `name()`. */
export type LogSourceName = 'LOG_SOURCE_CONTAINER' | 'LOG_SOURCE_CRON'

/**
 * One point on a chart, from a raw sample or from a bucket.
 *
 * The average and the maximum are both carried because they answer different questions: a
 * workload that averages 200 millicores and peaks at 1800 is spiking, and an average on
 * its own hides exactly that case. `memoryLimitBytes` is null for a bucket, because a
 * limit that changed mid-window has no single value.
 */
export interface MetricPoint {
  at: string
  cpuMillicores: number
  cpuMillicoresMax: number
  memoryBytes: number
  memoryBytesMax: number
  memoryLimitBytes: number | null
  diskBytes: number
  networkRxBytes: number
  networkTxBytes: number
  diskReadBytes: number
  diskWriteBytes: number
  restartCount: number
  /** How many readings went into this point. One for a raw sample. */
  sampleCount: number
}

/**
 * A chart's worth of data, with the window it actually covers.
 *
 * The window is echoed rather than assumed: asking for a year of a service created last
 * week produces points from last week, and drawing them across a year of empty axis looks
 * like a broken chart rather than a young service.
 */
export interface MetricSeries {
  /** The service, or the node for a machine-level chart. */
  subjectId: string
  source: MetricSource
  from: string
  to: string
  /** Oldest first, which is the order a chart draws in. */
  points: MetricPoint[]
  /** Derived from `isEmpty()`. */
  empty: boolean
}

/** `stats.MetricWindow`. `from` inclusive, `to` exclusive. */
export interface MetricWindow {
  from: string
  to: string
}

/**
 * One run of container output, as the log stream delivers it.
 *
 * A run of text and not a line: a container writes half a line and then thinks for ten
 * seconds, and buffering until a newline arrives makes a boot look hung. Splitting into
 * lines is the viewer's job, because only the viewer knows how wide the screen is.
 */
export interface LogEvent {
  text: string
  at: string
  stderr: boolean
  /** Output the node discarded rather than block the process writing it. Must be shown. */
  droppedBytes: number
  /** The source ended - the container exited, the cron run returned. */
  end: boolean
}
