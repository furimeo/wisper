import {Link, usePage} from '@inertiajs/react'

import {PHONE_TABS, isDestinationActive} from './NavigationDestinations'
import {Icon} from './Icon'
import {cx} from './cx'
import {t} from '@/i18n'

/**
 * The phone's navigation.
 *
 * Fixed to the bottom because that is the part of a 812px-tall screen a thumb reaches
 * while holding the device one-handed; a top navigation bar on a phone is a two-handed
 * control. Three destinations and a drawer button, each a full-height target across a
 * quarter of the width - well past the 44px floor even on a 320px screen.
 *
 * The bar carries its own safe-area padding, so its buttons clear the home indicator
 * while its background still reaches the bottom edge of the display.
 */
export function BottomTabBar({onOpenMenu}: {onOpenMenu: () => void}) {
  const url = usePage().url

  return (
    <nav
      aria-label="Sections"
      className={cx(
        'fixed inset-x-0 bottom-0 z-40 border-t border-ink-200 bg-white pb-safe md:hidden',
        'dark:border-ink-800 dark:bg-ink-900',
      )}
    >
      <ul className="flex items-stretch">
        {PHONE_TABS.map((tab) => {
          const active = isDestinationActive(url, tab)
          return (
            <li key={tab.href} className="flex-1">
              <Link
                href={tab.href}
                aria-current={active ? 'page' : undefined}
                className={cx(
                  'flex h-bottomnav flex-col items-center justify-center gap-0.5 text-[0.6875rem] font-medium',
                  active
                    ? 'text-accent-600 dark:text-accent-400'
                    : 'text-ink-500 dark:text-ink-400',
                )}
              >
                <Icon name={tab.icon} />
                {tab.labelKey ? t(tab.labelKey) : tab.label}
              </Link>
            </li>
          )
        })}
        <li className="flex-1">
          <button
            type="button"
            onClick={onOpenMenu}
            aria-haspopup="dialog"
            className="flex h-bottomnav w-full flex-col items-center justify-center gap-0.5 text-[0.6875rem] font-medium text-ink-500 dark:text-ink-400"
          >
            <Icon name="menu" />
            {t('shell.nav.menu')}
          </button>
        </li>
      </ul>
    </nav>
  )
}
