import type {ReactNode} from 'react'

import {cx} from './cx'
import {useIsWide} from './useMediaQuery'

/**
 * The title block at the top of a page's content, and the actions that belong to it.
 *
 * The heading only renders from `md` upward, because below it `Breadcrumbs` in the
 * sticky header is already the page's `<h1>` and two of them would be two headings for
 * one page - which is exactly what a screen reader reads out. The description and the
 * actions render at every width.
 *
 * Actions stack full-width on a phone. A row of three 90px buttons across a 375px screen
 * gives each of them a label of about one word.
 */
export interface PageHeaderProps {
  title: string
  description?: ReactNode
  /** One or two controls. Wrap more than that in a menu. */
  actions?: ReactNode
  className?: string
}

export function PageHeader({title, description, actions, className}: PageHeaderProps) {
  const wide = useIsWide()

  if (!description && !actions && !wide) {
    return null
  }

  return (
    <div
      className={cx(
        'flex flex-col gap-3 md:flex-row md:items-start md:justify-between',
        className,
      )}
    >
      <div className="min-w-0">
        {wide ? (
          <h1 className="text-xl font-semibold tracking-tight text-ink-900 dark:text-ink-50">
            {title}
          </h1>
        ) : null}
        {description ? (
          <p
            className={cx(
              'text-sm leading-relaxed text-ink-600 dark:text-ink-400',
              wide ? 'mt-1 max-w-prose' : '',
            )}
          >
            {description}
          </p>
        ) : null}
      </div>

      {actions ? (
        <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap md:shrink-0 md:justify-end">
          {actions}
        </div>
      ) : null}
    </div>
  )
}
