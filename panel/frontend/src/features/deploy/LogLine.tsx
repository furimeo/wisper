import {memo} from 'react'

import {cx} from '@/shell'

import type {DeploymentLog} from './deployTypes'
import {displayText} from './useLogWindow'

/**
 * The typography every part of the log viewer has to agree on.
 *
 * Exported because the width probe that decides how many characters fit on a line has to
 * be rendered with exactly these classes. If the probe and the rows disagree by a single
 * pixel of character width, every wrapped row is measured wrong and the virtualised
 * positions drift down the log.
 */
export const LOG_TEXT_CLASSES = 'font-mono text-[13px] leading-5'

/** Horizontal padding on a row, in pixels. `px-3`, and the width maths subtracts it. */
export const LOG_PADDING_X = 12

/** The line-number gutter, in pixels: `w-10` plus the `gap-3` after it. */
export const LOG_GUTTER_WIDTH = 40
export const LOG_GUTTER_GAP = 12

/**
 * One line of build output, positioned absolutely inside the log's spacer.
 *
 * Memoised, and that is not a micro-optimisation here: a streaming build re-renders the
 * viewer up to sixty times a second, and without this every row crossing the viewport is
 * rebuilt each time even though only the last one changed.
 *
 * `break-all` rather than `break-word` is what makes the height predictable - the browser
 * breaks on the character, so a row is exactly `ceil(characters / columns)` lines tall and
 * `useLogWindow` can say where the next one starts without laying anything out. It also
 * happens to be what a log wants: a 300-character path with no spaces in it should fill
 * the width rather than push the whole viewer sideways.
 */
export const LogLine = memo(function LogLine({
  line,
  top,
  height,
  showNumber,
}: {
  line: DeploymentLog
  top: number
  height: number
  /** The line-number gutter. Off on a phone, where the width is worth more as text. */
  showNumber: boolean
}) {
  const panel = line.stream === 'SYSTEM'
  const error = line.stream === 'STDERR'

  return (
    <div
      className={cx('absolute inset-x-0 flex gap-3 px-3', LOG_TEXT_CLASSES)}
      style={{top, height}}
    >
      {showNumber ? (
        <span
          aria-hidden="true"
          className="w-10 shrink-0 select-none text-right tabular-nums text-ink-600"
        >
          {line.sequence}
        </span>
      ) : null}
      <span
        className={cx(
          'min-w-0 flex-1 whitespace-pre-wrap break-all',
          panel ? 'text-accent-400 italic' : error ? 'text-failed' : 'text-ink-200',
        )}
      >
        {displayText(line.message)}
      </span>
    </div>
  )
})
