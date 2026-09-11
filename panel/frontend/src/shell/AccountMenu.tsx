import type {ReactNode} from 'react'
import {Link, router} from '@inertiajs/react'
import {useState} from 'react'

import {ACCOUNT_SETTINGS} from './NavigationDestinations'
import {Drawer} from './Drawer'
import {Icon} from './Icon'
import {Popover} from './Popover'
import {ThemeToggle} from './ThemeToggle'
import {cx} from './cx'
import {useIsWide} from './useMediaQuery'
import {useShellProps} from './shellProps'

/**
 * Who is signed in, their settings, the theme, and the way out.
 *
 * <p>Two shapes for two input devices, and the same contents in both. On a desktop it is
 * a popover under the button: a five-item menu does not need the height of the window,
 * and a panel that slides in from a screen edge reads as navigation rather than as a menu.
 * On a phone it is a drawer, because a 288px popover pinned to a corner is not something a
 * thumb can use and the platform's own menus are sheets.
 *
 * <p>The button shows the name rather than initials. Initials in a circle are a stand-in
 * for a photograph, and there are no photographs here - two letters in a grey disc tell
 * somebody nothing their own name would not tell them better, and the disc has to be big
 * enough to read, which is what was making the bar tall.
 *
 * <p>Signing out is a POST because `SecurityConfig` maps `/logout` as one - a GET that
 * ends a session can be triggered by an image tag on another site.
 */
export function AccountMenu({className}: {className?: string}) {
  const {account} = useShellProps()
  const wide = useIsWide()
  const [open, setOpen] = useState(false)

  if (!account) {
    return null
  }

  const trigger = (
    <button
      type="button"
      onClick={() => setOpen((was) => !was)}
      aria-haspopup={wide ? 'menu' : 'dialog'}
      aria-expanded={wide ? open : undefined}
      aria-label={`Account: ${account.displayName}`}
      className={cx(
        'flex min-w-0 shrink-0 items-center gap-1.5 rounded-lg px-2 py-1.5 text-sm font-medium',
        'text-ink-700 hover:bg-ink-100 dark:text-ink-200 dark:hover:bg-ink-800',
        className,
      )}
    >
      <span className="max-w-40 truncate">{account.displayName}</span>
      <Icon name="chevronDown" className="size-4 shrink-0 text-ink-500" />
    </button>
  )

  if (!wide) {
    return (
      <>
        {trigger}
        <Drawer open={open} onClose={() => setOpen(false)} side="right" title="Account">
          <Contents account={account} onNavigate={() => setOpen(false)} />
        </Drawer>
      </>
    )
  }

  return (
    <div className="relative shrink-0">
      {trigger}
      <Popover open={open} onClose={() => setOpen(false)} align="end">
        <Contents account={account} onNavigate={() => setOpen(false)} dense />
      </Popover>
    </div>
  )
}

/**
 * The menu itself, identical in both shapes.
 *
 * <p>`dense` is the only difference: a popover row is read with a mouse and a drawer row
 * is pressed with a thumb, and 44px targets in a popover make it twice as tall as it needs
 * to be.
 */
function Contents({
  account,
  onNavigate,
  dense = false,
}: {
  account: {displayName: string; email: string; platformAdmin: boolean}
  onNavigate: () => void
  dense?: boolean
}) {
  const row = dense
    ? 'flex items-center gap-3 px-3 py-2 text-sm'
    : 'flex touch-target items-center gap-3 rounded-lg px-3 text-sm'

  return (
    <>
      <div className={cx('px-3', dense ? 'py-2.5' : 'pb-3')}>
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

      <Section dense={dense}>
        {ACCOUNT_SETTINGS.map((entry) => (
          <li key={entry.href}>
            <Link
              href={entry.href}
              onClick={onNavigate}
              className={cx(
                row,
                'text-ink-800 hover:bg-ink-100 dark:text-ink-100 dark:hover:bg-ink-800',
              )}
            >
              <Icon name={entry.icon} className="size-4 text-ink-500" />
              {entry.label}
            </Link>
          </li>
        ))}
      </Section>

      <div
        className={cx(
          'border-t border-ink-200 px-3 dark:border-ink-800',
          dense ? 'py-2.5' : 'mt-4 pt-4',
        )}
      >
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-500 dark:text-ink-400">
          Theme
        </p>
        <ThemeToggle />
      </div>

      <div className={cx('border-t border-ink-200 dark:border-ink-800', dense ? '' : 'mt-4 pt-4')}>
        <button
          type="button"
          onClick={() => {
            onNavigate()
            router.post('/logout')
          }}
          className={cx(
            row,
            'w-full text-left font-medium text-failed hover:bg-failed/10',
            dense ? '' : 'rounded-lg',
          )}
        >
          <Icon name="signOut" className="size-4" />
          Sign out
        </button>
      </div>
    </>
  )
}

function Section({dense, children}: {dense: boolean; children: ReactNode}) {
  return (
    <ul
      className={cx(
        'flex flex-col border-t border-ink-200 dark:border-ink-800',
        dense ? 'py-1' : 'pt-2',
      )}
    >
      {children}
    </ul>
  )
}
