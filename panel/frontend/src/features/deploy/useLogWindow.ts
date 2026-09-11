import {useMemo, useRef} from 'react'

import type {DeploymentLog} from './deployTypes'

/**
 * Which log rows are worth putting in the DOM, and where each of them goes.
 *
 * A build log is the one screen in this panel that routinely has tens of thousands of
 * rows, and most customers open it on a phone. Thirty thousand `<div>`s is not slow, it is
 * a tab the browser kills - so only the rows crossing the viewport are rendered, inside a
 * spacer as tall as the whole log would be.
 *
 * The awkward part of virtualising a log is that rows are not all one line high. Fixing
 * the height would mean `white-space: pre` and a horizontally scrolling log, which on a
 * 375px screen hides the end of every stack trace. So lines wrap, and a wrapped row's
 * height is *computed* rather than measured: the font is monospace and `word-break:
 * break-all` makes the browser break on the character rather than the word, so a row is
 * exactly `ceil(characters / columns)` lines tall. Nothing has to be laid out to know how
 * tall it is, which is what keeps this O(new lines) as a build streams.
 *
 * The offsets are a running total kept across renders and extended in place. Appending a
 * batch touches only the batch; a rebuild happens on a resize, or on the rare trim when
 * the feed drops its oldest lines, and both are once-in-a-while events.
 */

/** How wide a tab renders. The viewer substitutes spaces so measuring and drawing agree. */
export const TAB_WIDTH = 4

const TAB_SPACES = ' '.repeat(TAB_WIDTH)

/** What is actually drawn for a line: tabs become spaces so the column count is honest. */
export function displayText(message: string): string {
  return message.includes('\t') ? message.replaceAll('\t', TAB_SPACES) : message
}

/** The same length without building the string - called for every line, drawn or not. */
function displayLength(message: string): number {
  let extra = 0
  for (let at = message.indexOf('\t'); at >= 0; at = message.indexOf('\t', at + 1)) {
    extra += TAB_WIDTH - 1
  }
  return message.length + extra
}

interface Measured {
  columns: number
  lineHeight: number
  count: number
  /** `offsets[i]` is the top of row `i`; `offsets[count]` is the height of the whole log. */
  offsets: number[]
}

/**
 * A fresh, deliberately impossible starting point.
 *
 * Per hook instance, never shared: two log viewers on one page holding the same offsets
 * array would extend each other's. The negative widths make the first `measure` call
 * rebuild rather than believe it can extend nothing.
 */
function unmeasured(): Measured {
  return {columns: -1, lineHeight: -1, count: 0, offsets: [0]}
}

export interface LogWindow {
  /** How tall the spacer has to be for the scrollbar to mean anything. */
  totalHeight: number
  /** First row to render, inclusive. */
  first: number
  /** Last row to render, exclusive. */
  last: number
  topOf: (index: number) => number
  heightOf: (index: number) => number
}

export function useLogWindow({
  lines,
  columns,
  lineHeight,
  scrollTop,
  viewportHeight,
  overscan = 8,
}: {
  lines: DeploymentLog[]
  /** How many characters fit on one visual line at the current width. */
  columns: number
  lineHeight: number
  scrollTop: number
  viewportHeight: number
  /** Rows drawn beyond each edge, so a flick does not reveal blank space. */
  overscan?: number
}): LogWindow {
  const cache = useRef<Measured | null>(null)

  const measured = useMemo(() => {
    const next = measure(cache.current ?? unmeasured(), lines, columns, lineHeight)
    cache.current = next
    return next
  }, [lines, columns, lineHeight])

  return useMemo(() => {
    const {offsets, count} = measured
    const totalHeight = offsets[count] ?? 0
    if (count === 0 || lineHeight <= 0) {
      return {
        totalHeight,
        first: 0,
        last: 0,
        topOf: () => 0,
        heightOf: () => lineHeight,
      }
    }

    const top = rowAt(offsets, count, scrollTop)
    const bottom = rowAt(offsets, count, scrollTop + Math.max(viewportHeight, 0))
    return {
      totalHeight,
      first: Math.max(0, top - overscan),
      last: Math.min(count, bottom + 1 + overscan),
      topOf: (index: number) => offsets[index] ?? 0,
      heightOf: (index: number) => (offsets[index + 1] ?? 0) - (offsets[index] ?? 0),
    }
  }, [measured, scrollTop, viewportHeight, overscan, lineHeight])
}

/**
 * Extends the running total, or rebuilds it when the shape of the text changed.
 *
 * Idempotent on purpose: React may invoke a memo's factory twice for one render, and the
 * second call has to see the work of the first and do nothing rather than double it.
 */
function measure(
  previous: Measured,
  lines: DeploymentLog[],
  columns: number,
  lineHeight: number,
): Measured {
  const reusable =
    previous.columns === columns &&
    previous.lineHeight === lineHeight &&
    previous.count <= lines.length
  if (reusable && previous.count === lines.length) {
    return previous
  }

  const offsets = reusable ? previous.offsets : [0]
  const from = reusable ? previous.count : 0
  let y = offsets[from] ?? 0

  for (let index = from; index < lines.length; index += 1) {
    const line = lines[index]
    const rows =
      line === undefined || columns <= 0
        ? 1
        : Math.max(1, Math.ceil(displayLength(line.message) / columns))
    y += rows * lineHeight
    offsets[index + 1] = y
  }
  // A rebuild after a trim leaves a longer array behind; the tail would be read by the
  // binary search and put rows in the wrong place.
  offsets.length = lines.length + 1

  return {columns, lineHeight, count: lines.length, offsets}
}

/** The index of the row containing `y`. */
function rowAt(offsets: number[], count: number, y: number): number {
  let low = 0
  let high = count
  while (low < high) {
    const middle = (low + high) >> 1
    if ((offsets[middle + 1] ?? 0) <= y) {
      low = middle + 1
    } else {
      high = middle
    }
  }
  return Math.min(low, count - 1)
}
