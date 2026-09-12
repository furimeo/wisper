import {useState} from 'react'

import {t} from '@/i18n'
import {cx} from '@/shell'

/**
 * One chart: a filled line per band, drawn straight into SVG.
 *
 * No charting library. The panel draws four of these and needs an area, an axis label and
 * a readout under the pointer; a library that does that costs more bytes than the whole
 * page and has to be kept working through its own major versions. Everything below is the
 * arithmetic for turning numbers into a path.
 *
 * `preserveAspectRatio="none"` lets the box be any shape without recomputing the geometry,
 * which is what makes the same chart work at 375px and at 1200px. The stroke is drawn with
 * `vector-effect="non-scaling-stroke"` so that stretching does not turn a 2px line into a
 * 9px wedge on a wide screen - which is the one thing that goes wrong with this approach.
 *
 * The readout is a pointer, not a tooltip. A tooltip on a touch screen is a hover state
 * that never arrives; a value shown in the header while a finger is on the chart is the
 * same information and works with a thumb.
 */
export interface ChartBand {
  label: string
  values: number[]
  /** A CSS colour. The palette's three status colours and the accent, mostly. */
  colour: string
  /** Drawn as a dashed line with no fill: a limit, not a measurement. */
  reference?: boolean
}

export function MetricChart({
  title,
  bands,
  timestamps,
  format,
  /** Forces the top of the axis, for a chart with a natural ceiling like a percentage. */
  maximum,
  className,
}: {
  title: string
  bands: ChartBand[]
  /** Rendered under the pointer. Same length as every band's values. */
  timestamps: string[]
  format: (value: number) => string
  maximum?: number
  className?: string
}) {
  const [cursor, setCursor] = useState<number | null>(null)

  const length = Math.max(0, ...bands.map((band) => band.values.length))
  const peak = Math.max(
    maximum ?? 0,
    ...bands.flatMap((band) => band.values),
    // A flat-zero chart still needs an axis, or every point lands on the baseline and the
    // line disappears into the border.
    1,
  )

  const at = cursor !== null && cursor < length ? cursor : length - 1
  const when = at >= 0 ? timestamps[at] : undefined

  return (
    <figure className={cx('flex flex-col gap-1', className)}>
      <figcaption className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
        <span className="text-sm font-medium text-ink-800 dark:text-ink-200">{title}</span>
        <span className="flex flex-wrap items-baseline gap-3 text-xs">
          {bands.map((band) => (
            <span key={band.label} className="flex items-center gap-1.5">
              <span
                aria-hidden="true"
                className="size-2 rounded-full"
                style={{backgroundColor: band.colour}}
              />
              <span className="text-ink-500 dark:text-ink-400">{band.label}</span>
              <span className="font-medium tabular-nums text-ink-900 dark:text-ink-100">
                {format(at >= 0 ? (band.values[at] ?? 0) : 0)}
              </span>
            </span>
          ))}
        </span>
      </figcaption>

      {length === 0 ? (
        <p className="rounded-lg border border-dashed border-ink-300 px-3 py-6 text-center text-sm text-ink-500 dark:border-ink-700 dark:text-ink-400">
          {t('stats.chart.empty')}
        </p>
      ) : (
        <div
          className="relative h-28 w-full touch-pan-y"
          onPointerMove={(event) => {
            const box = event.currentTarget.getBoundingClientRect()
            const fraction = (event.clientX - box.left) / Math.max(1, box.width)
            setCursor(Math.min(length - 1, Math.max(0, Math.round(fraction * (length - 1)))))
          }}
          onPointerLeave={() => setCursor(null)}
        >
          <svg
            viewBox="0 0 100 100"
            preserveAspectRatio="none"
            role="img"
            aria-label={t('stats.chart.aria', {title, count: length, peak: format(peak)})}
            className="h-full w-full overflow-visible rounded-lg bg-ink-100/60 dark:bg-ink-800/40"
          >
            {bands.map((band) => {
              const line = linePath(band.values, peak, length)
              return (
                <g key={band.label}>
                  {band.reference ? null : (
                    <path d={`${line} L 100 100 L 0 100 Z`} fill={band.colour} opacity={0.14} />
                  )}
                  <path
                    d={line}
                    fill="none"
                    stroke={band.colour}
                    strokeWidth={band.reference ? 1 : 1.75}
                    strokeDasharray={band.reference ? '3 3' : undefined}
                    strokeLinejoin="round"
                    strokeLinecap="round"
                    vectorEffect="non-scaling-stroke"
                  />
                </g>
              )
            })}
            {cursor !== null && length > 1 ? (
              <line
                x1={(cursor / (length - 1)) * 100}
                x2={(cursor / (length - 1)) * 100}
                y1={0}
                y2={100}
                stroke="currentColor"
                strokeWidth={1}
                className="text-ink-400"
                vectorEffect="non-scaling-stroke"
              />
            ) : null}
          </svg>
          <span className="pointer-events-none absolute left-1 top-1 rounded bg-white/80 px-1 text-[0.6875rem] tabular-nums text-ink-500 dark:bg-ink-900/80 dark:text-ink-400">
            {format(peak)}
          </span>
        </div>
      )}

      {when ? (
        <p className="text-right text-[0.6875rem] tabular-nums text-ink-500 dark:text-ink-400">
          {new Date(when).toLocaleString()}
        </p>
      ) : null}
    </figure>
  )
}

/**
 * The polyline through the values, in the 0-100 box.
 *
 * A single point would produce a path with one command and draw nothing, so it is
 * extended into a flat segment - a service that has reported once should show a line at
 * its value rather than an empty chart.
 */
function linePath(values: number[], peak: number, length: number): string {
  if (values.length === 0) {
    return 'M 0 100 L 100 100'
  }
  const step = length > 1 ? 100 / (length - 1) : 0
  const commands = values.map((value, index) => {
    const x = length > 1 ? index * step : 0
    const y = 100 - Math.max(0, Math.min(1, value / peak)) * 100
    return `${index === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`
  })
  if (values.length === 1) {
    const only = values[0] ?? 0
    const y = 100 - Math.max(0, Math.min(1, only / peak)) * 100
    return `M 0 ${y.toFixed(2)} L 100 ${y.toFixed(2)}`
  }
  return commands.join(' ')
}
