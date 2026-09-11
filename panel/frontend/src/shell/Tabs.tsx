import {Link, usePage} from '@inertiajs/react'
import type {ReactNode} from 'react'

import {cx} from './cx'

/**
 * The strip of sections across the top of a detail screen.
 *
 * A service has nine of them - overview, settings, environment, volumes, tasks,
 * deployments, domains, files, logs - and nine will not fit on a 375px screen at any font
 * size worth reading. So the strip scrolls horizontally, and it is the *only* thing on
 * the page that does: horizontal scrolling is fine when it is obviously a strip of tabs
 * and unforgivable when it is the page itself.
 *
 * An item with an `href` is a real navigation and renders an Inertia `Link`, which is
 * what the sub-pages of a service are. An item with a `value` is local state - the two
 * halves of one screen - and renders a button. One component because the strip looks and
 * behaves the same either way, and a page that mixed them would otherwise need two.
 */
export interface TabItem {
  /** Present for a navigation tab. Matched against the current URL. */
  href?: string
  /** Present for an in-page tab. Compared with `value`. */
  value?: string
  label: ReactNode
  /** A count, or a state dot. Kept small: the strip is already tight. */
  badge?: ReactNode
  disabled?: boolean
}

export interface TabsProps {
  items: TabItem[]
  /** The selected `value`, for in-page tabs. */
  value?: string
  onSelect?: (value: string) => void
  /** Announced as the purpose of the strip. */
  label: string
  className?: string
}

export function Tabs({items, value, onSelect, label, className}: TabsProps) {
  const currentUrl = usePage().url

  return (
    <nav
      aria-label={label}
      className={cx(
        'hide-scrollbar -mx-4 overflow-x-auto border-b border-ink-200 px-4 md:mx-0 md:px-0',
        'dark:border-ink-800',
        className,
      )}
    >
      <ul className="flex w-max min-w-full items-stretch gap-1">
        {items.map((item, index) => {
          const active =
            item.href !== undefined ? isCurrent(currentUrl, item.href) : item.value === value
          return (
            <li key={item.href ?? item.value ?? index}>
              {item.href !== undefined ? (
                <Link
                  href={item.href}
                  aria-current={active ? 'page' : undefined}
                  className={tabClasses(active, item.disabled)}
                >
                  {item.label}
                  {item.badge}
                </Link>
              ) : (
                <button
                  type="button"
                  disabled={item.disabled}
                  aria-current={active ? 'true' : undefined}
                  onClick={() => item.value !== undefined && onSelect?.(item.value)}
                  className={tabClasses(active, item.disabled)}
                >
                  {item.label}
                  {item.badge}
                </button>
              )}
            </li>
          )
        })}
      </ul>
    </nav>
  )
}

function tabClasses(active: boolean, disabled?: boolean): string {
  return cx(
    'flex touch-target items-center gap-2 whitespace-nowrap border-b-2 px-3 text-sm font-medium',
    'transition-colors',
    active
      ? 'border-accent-500 text-accent-600 dark:text-accent-400'
      : 'border-transparent text-ink-600 hover:text-ink-900 dark:text-ink-400 dark:hover:text-ink-100',
    disabled ? 'pointer-events-none opacity-50' : '',
  )
}

/**
 * Whether the current page is this tab's.
 *
 * Exact rather than prefix, plus the query string ignored: `/services/41` and
 * `/services/41/settings` are two tabs, and a prefix match would light both when the
 * second is open.
 */
function isCurrent(currentUrl: string, href: string): boolean {
  return path(currentUrl) === path(href)
}

function path(url: string): string {
  const cut = url.search(/[?#]/)
  const bare = cut < 0 ? url : url.slice(0, cut)
  return bare.length > 1 && bare.endsWith('/') ? bare.slice(0, -1) : bare
}
