import {useCallback, useEffect, useRef, useState} from 'react'

import {useEventSource} from '@/shell'

import type {MetricPoint, MetricSeries} from './statsTypes'

/**
 * A chart's data, live or historical, and the switch between the two.
 *
 * The panel offers two shapes of the same thing and they are not interchangeable
 * (`docs/contracts/pages.md` §7). `GET .../live` is Server-Sent Events: the recent window
 * arrives as one `series` frame and then a `point` frame per reading as the node pushes
 * it. `GET .../series?window=` is plain JSON for a window that is not moving. So "Live"
 * opens the stream and every other range closes it - a chart nobody is watching should
 * not hold an emitter open through a tunnel, and a fixed window has nothing to tail.
 *
 * The live series is trimmed by time rather than by count. A node pushes at its own
 * cadence and a workload that has been up for a week would otherwise grow the array in
 * this tab until the phone gives up.
 *
 * A dropped stream is not an error state. The browser reconnects, the server sends the
 * whole recent window again as a fresh `series` frame, and the chart is correct without
 * anything here having to reconcile a gap.
 */
export type MetricRange = 'live' | '1h' | '6h' | '24h' | '7d' | '30d'

export interface MetricFeed {
  series: MetricSeries
  range: MetricRange
  setRange: (range: MetricRange) => void
  /** The live tail is connected and delivering. */
  streaming: boolean
  /** A historical window is being fetched. */
  loading: boolean
  error: string | null
  retry: () => void
}

export function useMetricStream(
  basePath: string,
  initial: MetricSeries,
  liveWindowSeconds: number,
): MetricFeed {
  const [range, setRange] = useState<MetricRange>('live')
  const [series, setSeries] = useState<MetricSeries>(initial)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  // A navigation to another service re-renders this page with new props; without it the
  // chart would keep showing the previous subject's points until the stream caught up.
  const seeded = useRef(initial)
  if (seeded.current !== initial) {
    seeded.current = initial
    setSeries(initial)
  }

  const live = range === 'live'

  const stream = useEventSource<{series: MetricSeries; point: MetricPoint}>(
    live ? `${basePath}/live` : null,
    {
      series: (backfill) => {
        setSeries(backfill)
        setError(null)
      },
      point: (point) =>
        setSeries((current) => ({
          ...current,
          points: trim([...current.points, point], liveWindowSeconds),
          empty: false,
          to: point.at,
        })),
    },
    {enabled: live},
  )

  useEffect(() => {
    if (live) {
      return
    }
    const abort = new AbortController()
    setLoading(true)
    setError(null)
    fetch(`${basePath}/series?window=${range}`, {
      headers: {Accept: 'application/json'},
      credentials: 'same-origin',
      signal: abort.signal,
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`The panel answered ${response.status}.`)
        }
        return (await response.json()) as MetricSeries
      })
      .then((next) => {
        setSeries(next)
        setLoading(false)
      })
      .catch((cause: unknown) => {
        if (abort.signal.aborted) {
          return
        }
        setLoading(false)
        setError(
          cause instanceof Error
            ? cause.message
            : 'That window could not be read from the panel.',
        )
      })
    return () => abort.abort()
  }, [basePath, range, live, attempt])

  const retry = useCallback(() => {
    setAttempt((count) => count + 1)
  }, [])

  return {
    series,
    range,
    setRange,
    streaming: live && stream.status === 'open',
    loading,
    error,
    retry,
  }
}

/** Everything inside the live window, oldest first. */
function trim(points: MetricPoint[], windowSeconds: number): MetricPoint[] {
  const floor = Date.now() - windowSeconds * 1000
  const kept = points.filter((point) => Date.parse(point.at) >= floor)
  // A window so short that everything falls out of it would leave a chart that flickers
  // empty between readings; the newest point is always worth keeping.
  return kept.length > 0 ? kept : points.slice(-1)
}
