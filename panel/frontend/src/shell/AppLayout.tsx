import type {ReactNode} from 'react'
import {useEffect, useState} from 'react'
import {router} from '@inertiajs/react'

import {BottomTabBar} from './BottomTabBar'
import {ConfirmHost} from './ConfirmHost'
import {NavigationDrawer} from './NavigationDrawer'
import {PoweredBy} from './PoweredBy'
import {Sidebar} from './Sidebar'
import {Toaster} from './Toaster'
import {TopBar} from './TopBar'
import {useTheme} from './useTheme'

/**
 * The application chrome every signed-in page renders inside.
 *
 * Applied by `main.tsx` as Inertia's default layout rather than set on each page, which
 * means a page added later cannot forget it and render as a bare fragment on a white
 * background. The two screens that must not have chrome - sign-in and the second-factor
 * challenge - are named there, in one place, instead of being an opt-out every other page
 * has to remember not to trigger.
 *
 * Shape: a sidebar from `md` upward, a bottom tab bar below it, and one scroll region for
 * the content in both. The bottom bar is fixed, so the content region ends with enough
 * padding to clear it - a list whose last row sits under the navigation is a row nobody
 * can press.
 */
export function AppLayout({children}: {children: ReactNode}) {
  const [navOpen, setNavOpen] = useState(false)

  // Keeps the `dark` class in step with a `system` preference that changes while the tab
  // is open. Every other consumer of the theme is inside a menu that may not be mounted.
  useTheme()

  // A navigation that started somewhere other than the drawer - the bottom bar, a link
  // inside the page, the browser's back button - still has to close it.
  useEffect(() => router.on('start', () => setNavOpen(false)), [])

  return (
    <div className="min-h-dvh md:flex">
      <a
        href="#wisper-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50 focus:rounded-lg focus:bg-accent-600 focus:px-4 focus:py-2 focus:text-white"
      >
        Skip to content
      </a>

      <Sidebar />

      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar onOpenMenu={() => setNavOpen(true)} />

        <main
          id="wisper-content"
          className="mx-auto w-full max-w-5xl flex-1 px-4 py-4 pb-[calc(var(--spacing-bottomnav)+1.5rem)] md:px-6 md:py-6 md:pb-10"
        >
          {children}
        </main>

        {/*
         * Inside the content column and after the page, so it sits at the end of what the
         * reader was reading rather than pinned over it. Required by the licence - see
         * PoweredBy.
         */}
        <PoweredBy />
      </div>

      <BottomTabBar onOpenMenu={() => setNavOpen(true)} />
      <NavigationDrawer open={navOpen} onClose={() => setNavOpen(false)} />

      <Toaster />
      <ConfirmHost />
    </div>
  )
}
