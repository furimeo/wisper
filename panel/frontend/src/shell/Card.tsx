import type {ReactNode} from 'react'

import {cx} from './cx'

/**
 * A titled panel.
 *
 * The layout gives the main region a 16px gutter on a phone, so a card is a real card at
 * every width rather than going edge-to-edge below `md`. The header padding is smaller
 * than the desktop convention for the same reason: on 375px, chrome is width the content
 * does not get.
 */
export interface CardProps {
  title?: ReactNode
  description?: ReactNode
  /** Sits opposite the title: one button, or a small group of them. */
  action?: ReactNode
  /** Off when the body is a list that draws its own row padding. */
  padded?: boolean
  footer?: ReactNode
  children?: ReactNode
  className?: string
}

export function Card({
  title,
  description,
  action,
  padded = true,
  footer,
  children,
  className,
}: CardProps) {
  return (
    <section
      className={cx(
        'rounded-xl border border-ink-200 bg-white dark:border-ink-800 dark:bg-ink-900',
        className,
      )}
    >
      {title || action ? (
        <header
          className={cx(
            'flex items-start justify-between gap-3 px-4 py-3.5 md:px-5',
            children || footer ? 'border-b border-ink-200 dark:border-ink-800' : '',
          )}
        >
          <div className="min-w-0">
            {title ? (
              <h2 className="truncate text-sm font-semibold text-ink-900 dark:text-ink-100">
                {title}
              </h2>
            ) : null}
            {description ? (
              <p className="mt-1 text-sm text-ink-500 dark:text-ink-400">{description}</p>
            ) : null}
          </div>
          {action ? <div className="flex shrink-0 items-center gap-2">{action}</div> : null}
        </header>
      ) : null}

      {children ? <div className={padded ? 'px-4 py-4 md:px-5' : ''}>{children}</div> : null}

      {footer ? (
        <footer className="border-t border-ink-200 px-4 py-3 md:px-5 dark:border-ink-800">
          {footer}
        </footer>
      ) : null}
    </section>
  )
}

/** A label/value pair, the shape most of the panel's detail screens are made of. */
export function CardFact({label, children}: {label: ReactNode; children: ReactNode}) {
  return (
    <div className="flex flex-col gap-0.5 py-2">
      <dt className="text-xs font-medium uppercase tracking-wide text-ink-500 dark:text-ink-400">
        {label}
      </dt>
      <dd className="text-sm break-words text-ink-900 dark:text-ink-100">{children}</dd>
    </div>
  )
}

/** Facts in one column on a phone, two from `md` upward. */
export function CardFacts({children}: {children: ReactNode}) {
  return <dl className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">{children}</dl>
}
