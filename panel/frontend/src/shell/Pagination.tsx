import {Link} from '@inertiajs/react'

import {Button} from './Button'
import {Icon} from './Icon'
import {cx} from './cx'
import {t} from '@/i18n'

/**
 * Previous and next over an offset window.
 *
 * Offset, not cursor, because the two paged screens in the panel - the audit log and the
 * failed-job list - answer with `{total, offset, pageSize}` and the customer wants to
 * know how much is behind them. Numbered pages are deliberately absent: on a phone they
 * are seven 30px targets in a row, and nobody has ever needed page 14 of an audit log
 * when the filter above it exists.
 *
 * `hrefFor` makes each step a real URL, which keeps the back button and a shared link
 * working. `onNavigate` is for a window held in component state.
 */
export interface PaginationProps {
  total: number
  offset: number
  pageSize: number
  hrefFor?: (offset: number) => string
  onNavigate?: (offset: number) => void
  /** What is being counted, for the summary line: "41-60 of 128 entries". */
  unit?: string
  className?: string
}

export function Pagination({
  total,
  offset,
  pageSize,
  hrefFor,
  onNavigate,
  unit = 'items',
  className,
}: PaginationProps) {
  const size = Math.max(1, pageSize)
  const first = total === 0 ? 0 : offset + 1
  const last = Math.min(offset + size, total)
  const previousOffset = Math.max(0, offset - size)
  const nextOffset = offset + size

  const hasPrevious = offset > 0
  const hasNext = nextOffset < total

  return (
    <div
      className={cx(
        'flex flex-col gap-3 border-t border-ink-200 px-4 py-3 dark:border-ink-800',
        'sm:flex-row sm:items-center sm:justify-between',
        className,
      )}
    >
      <p className="text-sm text-ink-600 tabular-nums dark:text-ink-400">
        {total === 0 ? t('shell.pagination.empty', {unit}) : t('shell.pagination.summary', {from: first, to: last, total, unit})}
      </p>

      <div className="flex items-center gap-2">
        <Step
          direction="previous"
          enabled={hasPrevious}
          offset={previousOffset}
          hrefFor={hrefFor}
          onNavigate={onNavigate}
        />
        <Step
          direction="next"
          enabled={hasNext}
          offset={nextOffset}
          hrefFor={hrefFor}
          onNavigate={onNavigate}
        />
      </div>
    </div>
  )
}

function Step({
  direction,
  enabled,
  offset,
  hrefFor,
  onNavigate,
}: {
  direction: 'previous' | 'next'
  enabled: boolean
  offset: number
  hrefFor?: (offset: number) => string
  onNavigate?: (offset: number) => void
}) {
  const label = direction === 'previous' ? t('shell.pagination.previous') : t('shell.pagination.next')
  const icon = <Icon name={direction === 'previous' ? 'chevronLeft' : 'chevronRight'} />
  const body =
    direction === 'previous' ? (
      <>
        {icon}
        {label}
      </>
    ) : (
      <>
        {label}
        {icon}
      </>
    )

  const classes =
    'inline-flex touch-target items-center justify-center gap-1 rounded-lg border px-3 ' +
    'text-sm font-medium border-ink-300 bg-white text-ink-800 hover:bg-ink-100 ' +
    'dark:border-ink-700 dark:bg-ink-900 dark:text-ink-100 dark:hover:bg-ink-800'

  if (!enabled) {
    return (
      <span className={cx(classes, 'pointer-events-none opacity-50')} aria-disabled="true">
        {body}
      </span>
    )
  }

  if (hrefFor) {
    return (
      <Link href={hrefFor(offset)} preserveScroll className={classes}>
        {body}
      </Link>
    )
  }

  return (
    <Button variant="secondary" onClick={() => onNavigate?.(offset)}>
      {body}
    </Button>
  )
}
