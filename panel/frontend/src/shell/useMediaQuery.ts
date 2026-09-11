import {useCallback, useSyncExternalStore} from 'react'

/**
 * A media query as React state.
 *
 * The panel is mobile-first and most of that is CSS, but three things cannot be done in
 * CSS: rendering a table instead of a swipeable list, mounting a bottom sheet instead of
 * a centred dialog, and choosing how many breadcrumbs fit. Those need the answer in
 * JavaScript, and they need it to change when the phone is rotated.
 *
 * `useSyncExternalStore` rather than `useState` + `useEffect`: the first paint reads the
 * real value instead of rendering the wrong layout and correcting it, and React's own
 * tearing guarantees apply when two components ask the same question.
 */
export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      const list = window.matchMedia(query)
      list.addEventListener('change', onChange)
      return () => list.removeEventListener('change', onChange)
    },
    [query],
  )

  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia(query).matches,
    // There is no server render here - InertiaPage.java writes the shell and React
    // mounts on the client - but React still asks, and "phone" is the honest default.
    () => false,
  )
}

/** Tailwind's `md`. Above it the chrome is a sidebar and lists become tables. */
export const AT_LEAST_MD = '(min-width: 48rem)'

/** Tailwind's `lg`, where the sidebar stops being collapsible. */
export const AT_LEAST_LG = '(min-width: 64rem)'

/** Set by the reader, honoured by every animation in the shell. */
export const PREFERS_REDUCED_MOTION = '(prefers-reduced-motion: reduce)'

/** True from the `md` breakpoint upward. */
export function useIsWide(): boolean {
  return useMediaQuery(AT_LEAST_MD)
}
