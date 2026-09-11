import type {ReactNode} from 'react'

import {cx} from './cx'

/**
 * What a screen shows when it has nothing to list.
 *
 * This is the component that exists because of the predecessor project: it shipped seven
 * screens that rendered an empty frame, and a customer could not tell a page that was
 * broken from one that was simply new. So an empty state here is not decoration, and the
 * `description` is not optional in practice - it says what to do next, and `action` is
 * the button that does it.
 */
export interface EmptyStateProps {
  /** A 24px line icon. Optional: a sentence is worth more than a picture here. */
  icon?: ReactNode
  title: string
  /** What this list is for, and what creating the first one would mean. */
  description: ReactNode
  action?: ReactNode
  className?: string
}

export function EmptyState({icon, title, description, action, className}: EmptyStateProps) {
  return (
    <div
      className={cx(
        'flex flex-col items-center gap-3 px-6 py-12 text-center',
        className,
      )}
    >
      {icon ? (
        <span className="flex size-11 items-center justify-center rounded-full bg-ink-100 text-ink-500 dark:bg-ink-800 dark:text-ink-400">
          {icon}
        </span>
      ) : null}
      <h3 className="text-base font-semibold text-ink-900 dark:text-ink-100">{title}</h3>
      <p className="max-w-prose text-sm leading-relaxed text-ink-600 dark:text-ink-400">
        {description}
      </p>
      {action ? <div className="mt-1">{action}</div> : null}
    </div>
  )
}
