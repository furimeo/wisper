import {usePage} from '@inertiajs/react'
import {useEffect, useRef, useSyncExternalStore} from 'react'

import {cx} from './cx'
import {Icon} from './Icon'
import {useShellProps} from './shellProps'
import type {Toast, ToastTone} from './toastStore'
import {currentToasts, dismissToast, showToast, subscribeToToasts} from './toastStore'

/**
 * Renders the notice queue, and feeds the server's flash messages into it.
 *
 * Mounted once by `AppLayout`. Above the bottom navigation on a phone rather than at the
 * top of the screen: a notice at the top of a 812px-tall display is nowhere near the
 * thumb that has to dismiss it, and it lands on top of the breadcrumbs.
 *
 * `aria-live="polite"` so a screen reader announces the notice without interrupting, and
 * `role="alert"` on the failures, which should interrupt.
 */
const TONES: Record<ToastTone, {panel: string; icon: 'check' | 'close' | 'inbox'}> = {
  success: {panel: 'border-running/40 bg-white dark:bg-ink-900', icon: 'check'},
  error: {panel: 'border-failed/50 bg-white dark:bg-ink-900', icon: 'close'},
  info: {panel: 'border-ink-300 bg-white dark:border-ink-700 dark:bg-ink-900', icon: 'inbox'},
}

const MARKS: Record<ToastTone, string> = {
  success: 'bg-running/15 text-running',
  error: 'bg-failed/15 text-failed',
  info: 'bg-accent-500/10 text-accent-600 dark:text-accent-400',
}

export function Toaster() {
  const toasts = useSyncExternalStore(subscribeToToasts, currentToasts, currentToasts)
  useFlashToasts()

  if (toasts.length === 0) {
    return null
  }

  return (
    <div
      aria-live="polite"
      className={cx(
        'pointer-events-none fixed inset-x-0 z-50 flex flex-col items-center gap-2 px-3',
        // Clear of the bottom navigation on a phone; bottom-right on a wide screen, where
        // the middle of the viewport is where the content is.
        'bottom-[calc(var(--spacing-bottomnav)+0.75rem)]',
        'md:bottom-4 md:right-4 md:left-auto md:items-end',
      )}
    >
      {toasts.map((entry) => (
        <ToastCard key={entry.id} toast={entry} />
      ))}
    </div>
  )
}

function ToastCard({toast: entry}: {toast: Toast}) {
  useEffect(() => {
    if (entry.durationMs <= 0) {
      return
    }
    const timer = window.setTimeout(() => dismissToast(entry.id), entry.durationMs)
    return () => window.clearTimeout(timer)
  }, [entry.id, entry.durationMs])

  const tone = TONES[entry.tone]

  return (
    <div
      role={entry.tone === 'error' ? 'alert' : undefined}
      data-panel="toast"
      className={cx(
        'pointer-events-auto flex w-full max-w-md items-start gap-3',
        'rounded-xl border px-3 py-2.5 shadow-lg',
        tone.panel,
      )}
    >
      <span
        aria-hidden="true"
        className={cx(
          'mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full',
          MARKS[entry.tone],
        )}
      >
        <Icon name={tone.icon} className="size-4" />
      </span>
      <p className="min-w-0 flex-1 text-sm break-words text-ink-800 dark:text-ink-100">
        {entry.message}
      </p>
      <button
        type="button"
        onClick={() => dismissToast(entry.id)}
        aria-label="Dismiss"
        className="-my-1 -mr-1 flex size-9 shrink-0 items-center justify-center rounded-lg text-ink-500 hover:bg-ink-100 dark:hover:bg-ink-800"
      >
        <Icon name="close" className="size-4" />
      </button>
    </div>
  )
}

/**
 * Turns `flash.success` and `flash.error` into notices, exactly once each.
 *
 * A flash survives one redirect, so it appears in the props of the page that redirect
 * landed on and then never again. The page's URL together with the message is what
 * identifies it: without that, StrictMode's double-invoked effect raises every notice
 * twice in development, and a re-render for any other reason raises it again in
 * production.
 */
function useFlashToasts(): void {
  const {flash} = useShellProps()
  const url = usePage().url
  const lastRaised = useRef<string | null>(null)

  useEffect(() => {
    const parts: Array<['success' | 'error', string]> = []
    if (flash.success) {
      parts.push(['success', flash.success])
    }
    if (flash.error) {
      parts.push(['error', flash.error])
    }
    if (parts.length === 0) {
      return
    }
    const signature = `${url}::${parts.map(([tone, text]) => `${tone}:${text}`).join('|')}`
    if (lastRaised.current === signature) {
      return
    }
    lastRaised.current = signature
    for (const [tone, message] of parts) {
      showToast({tone, message})
    }
  }, [flash, url])
}
