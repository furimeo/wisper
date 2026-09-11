import type {ReactNode} from 'react'

import {cx} from './cx'

/**
 * A state pill.
 *
 * The three status colours are the ones the platform actually reports - running,
 * degraded, failed - and they are the same three in the theme, so a pill and a chart and
 * a node's dot cannot disagree about what "degraded" looks like. `neutral` is for a state
 * that is not a health judgement: queued, stopped, archived.
 *
 * A dot as well as a colour, because roughly one reader in twelve cannot tell the green
 * from the amber, and "the service is fine" is not something to encode in hue alone.
 */
export type BadgeTone = 'neutral' | 'accent' | 'running' | 'degraded' | 'failed'

/*
 * Tint plus a coloured dot, with the label itself in the neutral ink.
 *
 * Colouring the text as well reads better in a mock-up and fails in daylight: the amber
 * this palette uses for `degraded` is light enough that amber-on-tint drops under 4.5:1,
 * and a status nobody can read outdoors is worse than a plain one. The dot and the
 * border carry the colour; the word carries the meaning.
 */
const TONES: Record<BadgeTone, string> = {
  neutral: 'bg-ink-100 border-ink-200 dark:bg-ink-800 dark:border-ink-700',
  accent: 'bg-accent-500/10 border-accent-500/30',
  running: 'bg-running/10 border-running/30',
  degraded: 'bg-degraded/20 border-degraded/30',
  failed: 'bg-failed/10 border-failed/30',
}

const DOTS: Record<BadgeTone, string> = {
  neutral: 'bg-ink-400',
  accent: 'bg-accent-500',
  running: 'bg-running',
  degraded: 'bg-degraded',
  failed: 'bg-failed',
}

export interface BadgeProps {
  tone?: BadgeTone
  /** Draws the leading dot. On for anything reporting a state. */
  dot?: boolean
  /** Adds a quiet pulse - for a state that is still moving, like BUILDING. */
  pulse?: boolean
  children: ReactNode
  className?: string
}

export function Badge({tone = 'neutral', dot, pulse, children, className}: BadgeProps) {
  return (
    <span
      className={cx(
        'inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5',
        'text-xs font-medium whitespace-nowrap',
        'text-ink-800 dark:text-ink-100',
        TONES[tone],
        className,
      )}
    >
      {dot ? (
        <span
          aria-hidden="true"
          className={cx('size-1.5 rounded-full', DOTS[tone], pulse ? 'animate-pulse' : '')}
        />
      ) : null}
      {children}
    </span>
  )
}
