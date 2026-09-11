import {Link, usePage} from '@inertiajs/react'

import type {Destination} from './NavigationDestinations'
import {
  ACCOUNT_SETTINGS,
  PLATFORM,
  WORKSPACE,
  isDestinationActive,
} from './NavigationDestinations'
import {Icon} from './Icon'
import {cx} from './cx'
import {useShellProps} from './shellProps'

/**
 * The sections of the navigation, rendered the same way in the sidebar and in the phone
 * drawer.
 *
 * One component for both so a screen cannot be reachable on a desktop and missing on a
 * phone. The platform section appears only for an operator - `SecurityConfig` requires
 * `ROLE_ADMIN` on `/admin/**` regardless, so this decides what is drawn and never what is
 * allowed.
 */
export function NavigationList({onNavigate}: {onNavigate?: () => void}) {
  const {account} = useShellProps()
  const url = usePage().url

  return (
    <nav aria-label="Main" className="flex flex-col gap-5">
      <Section items={WORKSPACE} url={url} onNavigate={onNavigate} />
      <Section title="Account" items={ACCOUNT_SETTINGS} url={url} onNavigate={onNavigate} />
      {account?.platformAdmin ? (
        <Section title="Platform" items={PLATFORM} url={url} onNavigate={onNavigate} />
      ) : null}
    </nav>
  )
}

function Section({
  title,
  items,
  url,
  onNavigate,
}: {
  title?: string
  items: Destination[]
  url: string
  onNavigate?: () => void
}) {
  return (
    <div>
      {title ? (
        <p className="px-3 pb-1.5 text-xs font-semibold uppercase tracking-wide text-ink-500 dark:text-ink-400">
          {title}
        </p>
      ) : null}
      <ul className="flex flex-col gap-0.5">
        {items.map((item) => {
          const active = isDestinationActive(url, item)
          return (
            <li key={item.href}>
              <Link
                href={item.href}
                onClick={onNavigate}
                aria-current={active ? 'page' : undefined}
                className={cx(
                  'flex touch-target items-center gap-3 rounded-lg px-3 text-sm font-medium',
                  'transition-colors',
                  active
                    ? 'bg-accent-500/10 text-accent-600 dark:text-accent-400'
                    : 'text-ink-700 hover:bg-ink-100 dark:text-ink-300 dark:hover:bg-ink-800',
                )}
              >
                <Icon
                  name={item.icon}
                  className={cx('size-5', active ? '' : 'text-ink-500 dark:text-ink-400')}
                />
                <span className="truncate">{item.label}</span>
              </Link>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
