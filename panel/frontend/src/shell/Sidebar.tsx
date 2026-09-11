import {Link} from '@inertiajs/react'

import {NavigationList} from './NavigationList'
import {OrganizationSwitcher} from './OrganizationSwitcher'

/**
 * The permanent navigation, from `md` upward.
 *
 * Sticky and its own scroll region, so a long platform section does not push the
 * organization switcher off the top and a long page does not carry the navigation away.
 * Below `md` this is not rendered at all - `BottomTabBar` and the drawer are the
 * navigation there.
 */
export function Sidebar() {
  return (
    <aside className="hidden w-64 shrink-0 border-r border-ink-200 bg-white md:block dark:border-ink-800 dark:bg-ink-900">
      <div className="sticky top-0 flex h-dvh flex-col gap-4 overflow-y-auto px-3 py-4">
        <Link
          href="/"
          className="flex items-center gap-2 px-2 text-base font-semibold tracking-tight text-ink-900 dark:text-ink-50"
        >
          <span className="flex size-7 items-center justify-center rounded-md bg-accent-600 text-sm font-bold text-white">
            w
          </span>
          wisper
        </Link>

        <OrganizationSwitcher className="w-full border border-ink-200 dark:border-ink-700" />

        <NavigationList />
      </div>
    </aside>
  )
}
