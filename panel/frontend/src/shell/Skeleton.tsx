import {cx} from './cx'

/**
 * Placeholders for content that is on its way.
 *
 * Most of the panel arrives with the page - Inertia sends the props with the document -
 * so these are for the parts that do not: a metric series fetched after mount, a
 * directory listing being re-read, an SSE backfill. A skeleton the same shape as the real
 * thing keeps the page from jumping when the data lands, which matters more on a phone
 * where a jump moves the thing the thumb was about to press.
 *
 * `animate-pulse` is Tailwind's, and `prefers-reduced-motion` switches it off in
 * `styles.css` rather than here.
 */
export function Skeleton({className}: {className?: string}) {
  return (
    <span
      aria-hidden="true"
      className={cx('block animate-pulse rounded bg-ink-200 dark:bg-ink-800', className)}
    />
  )
}

/** A paragraph's worth of lines, the last one short so it reads as text. */
export function SkeletonText({lines = 3, className}: {lines?: number; className?: string}) {
  return (
    <div className={cx('flex flex-col gap-2', className)}>
      {Array.from({length: lines}, (_, index) => (
        <Skeleton
          key={index}
          className={cx('h-4', index === lines - 1 ? 'w-2/5' : 'w-full')}
        />
      ))}
    </div>
  )
}

/**
 * Rows shaped like `DataList`'s, for a list that is still loading.
 *
 * `label` is what a screen reader hears; without it the whole region is silent and
 * somebody using one has no way to know anything is happening.
 */
export function SkeletonList({
  rows = 4,
  label = 'Loading',
  className,
}: {
  rows?: number
  label?: string
  className?: string
}) {
  return (
    <div
      role="status"
      aria-live="polite"
      aria-label={label}
      className={cx('divide-y divide-ink-200 dark:divide-ink-800', className)}
    >
      {Array.from({length: rows}, (_, index) => (
        <div key={index} className="flex items-center gap-3 px-4 py-3.5">
          <div className="min-w-0 flex-1 space-y-2">
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-3 w-3/4" />
          </div>
          <Skeleton className="h-6 w-16 shrink-0 rounded-full" />
        </div>
      ))}
    </div>
  )
}
