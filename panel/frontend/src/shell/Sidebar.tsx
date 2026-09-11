import {Link} from '@inertiajs/react'

import {NavigationList} from './NavigationList'
import {OrganizationSwitcher} from './OrganizationSwitcher'

/**
 * The permanent navigation, from `md` upward.
 *
 * Sticky, and only the list of destinations scrolls. The mark and the organization
 * switcher are pinned above it because they are what tells you which installation and
 * which tenant you are looking at - scrolling those away to reach `Jobs` is how somebody
 * ends up acting on the wrong organization.
 *
 * The scroll region is on the list rather than on the whole column for a second reason:
 * with fifteen destinations the column overflows a 768px-tall window, and a scrollbar
 * running the full height of the sidebar sits directly beside the page's own scrollbar.
 * Two scrollbars a few pixels apart read as a rendering fault rather than as two regions.
 *
 * Below `md` this is not rendered at all - `BottomTabBar` and the drawer are the
 * navigation there.
 */
export function Sidebar() {
  return (
    <aside className="hidden w-64 shrink-0 border-r border-ink-200 bg-white md:block dark:border-ink-800 dark:bg-ink-900">
      <div className="sticky top-0 flex h-dvh flex-col gap-4 overflow-hidden px-3 py-4">
        <Link
          href="/"
          className="flex shrink-0 items-center gap-2 px-2 text-base font-semibold tracking-tight text-ink-900 dark:text-ink-50"
        >
          <span className="flex size-7 items-center justify-center rounded-md bg-accent-600 text-sm font-bold text-white">
            w
          </span>
          wisper
        </Link>

        <OrganizationSwitcher className="w-full shrink-0 border border-ink-200 dark:border-ink-700" />

        {/*
         * `min-h-0` is load-bearing: a flex child defaults to min-height:auto, which is
         * its content height, so without it the list refuses to shrink and overflows the
         * column instead of scrolling inside it.
         */}
        <div className="-mr-1 min-h-0 flex-1 overflow-y-auto pr-1 scrollbar-thin">
          <NavigationList />
        </div>
      </div>
    </aside>
  )
}
