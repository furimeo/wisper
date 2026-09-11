import {Link, router} from '@inertiajs/react'
import {useState} from 'react'

import {ACCOUNT_SETTINGS} from './NavigationDestinations'
import {Drawer} from './Drawer'
import {Icon} from './Icon'
import {ThemeToggle} from './ThemeToggle'
import {cx} from './cx'
import {useShellProps} from './shellProps'

/**
 * Who is signed in, their settings, the theme, and the way out.
 *
 * A drawer rather than a hover menu: a hover menu has no equivalent on a touch screen,
 * and every attempt to make one work with a tap ends up reimplementing a dialog with
 * worse focus handling than the platform's.
 *
 * Signing out is a POST because `SecurityConfig` maps `/logout` as one - a GET that ends
 * a session can be triggered by an image tag on another site.
 */
export function AccountMenu({className}: {className?: string}) {
  const {account} = useShellProps()
  const [open, setOpen] = useState(false)

  if (!account) {
    return null
  }

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-haspopup="dialog"
        aria-label={`Account: ${account.displayName}`}
        className={cx(
          'flex size-11 shrink-0 items-center justify-center rounded-full',
          'bg-ink-200 text-sm font-semibold text-ink-700',
          'hover:bg-ink-300 dark:bg-ink-800 dark:text-ink-200 dark:hover:bg-ink-700',
          className,
        )}
      >
        {initials(account.displayName, account.email)}
      </button>

      <Drawer open={open} onClose={() => setOpen(false)} side="right" title="Account">
        <div className="px-3 pb-3">
          <p className="truncate text-sm font-medium text-ink-900 dark:text-ink-100">
            {account.displayName}
          </p>
          <p className="truncate text-sm text-ink-500 dark:text-ink-400">{account.email}</p>
          {account.platformAdmin ? (
            <p className="mt-1 text-xs font-medium text-accent-600 dark:text-accent-400">
              Platform operator
            </p>
          ) : null}
        </div>

        <ul className="flex flex-col border-t border-ink-200 pt-2 dark:border-ink-800">
          {ACCOUNT_SETTINGS.map((entry) => (
            <li key={entry.href}>
              <Link
                href={entry.href}
                onClick={() => setOpen(false)}
                className="flex touch-target items-center gap-3 rounded-lg px-3 text-sm text-ink-800 hover:bg-ink-100 dark:text-ink-100 dark:hover:bg-ink-800"
              >
                <Icon name={entry.icon} className="size-4 text-ink-500" />
                {entry.label}
              </Link>
            </li>
          ))}
        </ul>

        <div className="mt-4 border-t border-ink-200 px-3 pt-4 dark:border-ink-800">
          <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-500 dark:text-ink-400">
            Theme
          </p>
          <ThemeToggle />
        </div>

        <button
          type="button"
          onClick={() => {
            setOpen(false)
            router.post('/logout')
          }}
          className="mt-4 flex w-full touch-target items-center gap-3 rounded-lg px-3 text-left text-sm font-medium text-failed hover:bg-failed/10"
        >
          <Icon name="signOut" className="size-4" />
          Sign out
        </button>
      </Drawer>
    </>
  )
}

function initials(displayName: string, email: string): string {
  const source = displayName.trim().length > 0 ? displayName.trim() : email
  const words = source.split(/[\s@._-]+/).filter((word) => word.length > 0)
  const first = words[0]?.charAt(0) ?? source.charAt(0)
  const second = words.length > 1 ? (words[1]?.charAt(0) ?? '') : ''
  return `${first}${second}`.toUpperCase()
}
