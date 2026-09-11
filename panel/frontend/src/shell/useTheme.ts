import {useEffect, useSyncExternalStore} from 'react'

import {useMediaQuery} from './useMediaQuery'

/**
 * Light, dark, or whatever the device says.
 *
 * The default is the operating system's preference, which is what
 * `prefers-color-scheme` reports; the override exists because a phone that switches to
 * dark at sunset is not always what somebody reading a log wants. The choice is stored
 * under the same key `InertiaPage.java` reads in its inline bootstrap script, so the
 * class is on `<html>` before the first paint and a reader on a dark theme never sees a
 * white flash. Changing that key here without changing it there is a flash of white on
 * every navigation.
 */
export type ThemePreference = 'light' | 'dark' | 'system'

/** Must match `InertiaPage.THEME_BOOTSTRAP`. */
const STORAGE_KEY = 'wisper-theme'

const PREFERS_DARK = '(prefers-color-scheme: dark)'

const listeners = new Set<() => void>()
let preference: ThemePreference = readStoredPreference()

function readStoredPreference(): ThemePreference {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored === 'light' || stored === 'dark' || stored === 'system') {
      return stored
    }
  } catch {
    // Private mode, or storage disabled entirely. Following the device is a fine answer
    // and is exactly what the server's bootstrap script falls back to.
  }
  return 'system'
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Records the choice and tells every mounted consumer. */
export function setThemePreference(next: ThemePreference): void {
  preference = next
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // The choice still applies to this tab; it just will not survive a reload.
  }
  for (const listener of listeners) {
    listener()
  }
}

export interface Theme {
  /** What the reader chose. */
  preference: ThemePreference
  /** What that resolves to right now, with `system` answered by the device. */
  resolved: 'light' | 'dark'
  set: (next: ThemePreference) => void
}

/**
 * The current theme, and the way to change it.
 *
 * Applying the class is idempotent, so it is safe for several components to hold this
 * hook - the toggle in the header and the layout itself both do.
 */
export function useTheme(): Theme {
  const chosen = useSyncExternalStore(subscribe, () => preference, () => 'system' as const)
  const deviceIsDark = useMediaQuery(PREFERS_DARK)
  const resolved: 'light' | 'dark' =
    chosen === 'system' ? (deviceIsDark ? 'dark' : 'light') : chosen

  useEffect(() => {
    const root = document.documentElement
    root.classList.toggle('dark', resolved === 'dark')
    // Scrollbars, form controls and the address bar follow this rather than the class,
    // so an override that only flipped the class would leave native widgets light.
    root.style.colorScheme = resolved
  }, [resolved])

  return {preference: chosen, resolved, set: setThemePreference}
}
