import type {ReactNode} from 'react'

import {Button} from './Button'
import {cx} from './cx'

/**
 * A failure inside a page that otherwise rendered.
 *
 * Distinct from `features/error/ErrorPage`, which answers a request that never produced
 * a page at all. This one is for the part of a screen that could not be filled: the node
 * holding these files is unreachable, the metric series could not be read, the log stream
 * dropped. The panel has a name for that state on the server too - the file manager's
 * `unavailable` prop is exactly this sentence - and showing it beats an empty folder that
 * claims the customer's files are gone.
 *
 * `onRetry` is only offered when retrying is genuinely a different attempt. A refresh
 * button that repeats a request which will fail the same way is a button that teaches
 * people the panel is broken.
 */
export interface ErrorStateProps {
  title?: string
  /** The sentence the server sent, or one that says what could not be reached. */
  description: ReactNode
  onRetry?: () => void
  retryLabel?: string
  /** A second way out - a link back to something that does work. */
  action?: ReactNode
  className?: string
}

export function ErrorState({
  title = 'This part could not be loaded',
  description,
  onRetry,
  retryLabel = 'Try again',
  action,
  className,
}: ErrorStateProps) {
  return (
    <div
      role="alert"
      className={cx('flex flex-col items-center gap-3 px-6 py-10 text-center', className)}
    >
      <span
        aria-hidden="true"
        className="flex size-11 items-center justify-center rounded-full bg-failed/10 text-failed"
      >
        <svg viewBox="0 0 24 24" fill="none" className="size-6">
          <path
            d="M12 8.5v4.25M12 16.2h.01M10.3 4.2 2.9 17.4A1.9 1.9 0 0 0 4.6 20.2h14.8a1.9 1.9 0 0 0 1.7-2.8L13.7 4.2a1.9 1.9 0 0 0-3.4 0Z"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </span>
      <h3 className="text-base font-semibold text-ink-900 dark:text-ink-100">{title}</h3>
      <p className="max-w-prose text-sm leading-relaxed text-ink-600 dark:text-ink-400">
        {description}
      </p>
      {onRetry || action ? (
        <div className="mt-1 flex flex-wrap items-center justify-center gap-2">
          {onRetry ? (
            <Button variant="secondary" onClick={onRetry}>
              {retryLabel}
            </Button>
          ) : null}
          {action}
        </div>
      ) : null}
    </div>
  )
}
