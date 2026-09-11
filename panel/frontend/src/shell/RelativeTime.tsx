import {useEffect, useState} from 'react'

/**
 * "3 min ago", with the exact moment on hover and in the accessible name.
 *
 * Almost every timestamp in the panel answers "is this current?" rather than "when
 * exactly?" - the last heartbeat, the last deployment, when a certificate was obtained.
 * A relative phrase answers that at a glance; the absolute value is a `title` away for
 * the one time in ten it is the question.
 *
 * The clock advances while the page is open, because a heartbeat that says "12 sec ago"
 * for twenty minutes is worse than no heartbeat: it reads as fresh. Anything older than a
 * day stops ticking, since nothing visible changes minute to minute at that scale.
 */
const FORMATTER = new Intl.RelativeTimeFormat(undefined, {numeric: 'auto'})

const UNITS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ['year', 365 * 24 * 3600_000],
  ['month', 30 * 24 * 3600_000],
  ['day', 24 * 3600_000],
  ['hour', 3600_000],
  ['minute', 60_000],
  ['second', 1000],
]

export interface RelativeTimeProps {
  /** ISO-8601, as Jackson serialises an `Instant`. Null renders `fallback`. */
  at: string | null | undefined
  /** What to show when there is no timestamp. */
  fallback?: string
  className?: string
}

export function RelativeTime({at, fallback = '-', className}: RelativeTimeProps) {
  const [, tick] = useState(0)
  const parsed = at ? Date.parse(at) : Number.NaN
  const valid = Number.isFinite(parsed)
  const age = valid ? Date.now() - parsed : 0

  useEffect(() => {
    if (!valid || Math.abs(age) >= 24 * 3600_000) {
      return
    }
    // Once a minute is enough: the phrase itself has minute resolution above the first
    // minute, and a second-by-second timer on a list of forty rows is forty timers.
    const period = Math.abs(age) < 60_000 ? 5_000 : 60_000
    const timer = window.setInterval(() => tick((value) => value + 1), period)
    return () => window.clearInterval(timer)
  }, [valid, age])

  if (!valid) {
    return <span className={className}>{fallback}</span>
  }

  return (
    <time dateTime={at ?? undefined} title={absolute(parsed)} className={className}>
      {relative(parsed)}
    </time>
  )
}

/** The phrase, from the largest unit that fits. */
export function relative(epochMillis: number, now = Date.now()): string {
  const differenceMs = epochMillis - now
  const magnitude = Math.abs(differenceMs)
  if (magnitude < 5_000) {
    return 'just now'
  }
  for (const [unit, size] of UNITS) {
    if (magnitude >= size) {
      return FORMATTER.format(Math.round(differenceMs / size), unit)
    }
  }
  return FORMATTER.format(Math.round(differenceMs / 1000), 'second')
}

/** The full local moment, for the tooltip. */
export function absolute(epochMillis: number): string {
  return new Date(epochMillis).toLocaleString(undefined, {
    dateStyle: 'medium',
    timeStyle: 'medium',
  })
}
