import {useCallback, useEffect, useRef, useState} from 'react'

/** What the last copy attempt did. `failed` is shown, not swallowed. */
export type ClipboardState = 'idle' | 'copied' | 'failed'

export interface Clipboard {
  copy: (text: string) => Promise<boolean>
  state: ClipboardState
}

/**
 * Copying to the clipboard, with the answer the caller can render.
 *
 * The panel hands out a lot of strings nobody retypes: connection strings, install
 * commands, checksums, API tokens shown exactly once. On a phone, selecting text inside a
 * scrollable row is close to impossible, so the copy button is not a convenience.
 *
 * Two paths, because the asynchronous Clipboard API is unavailable outside a secure
 * context and a panel reached over plain HTTP on a private network is a real
 * deployment. The fallback uses a detached textarea and `execCommand`, which is
 * deprecated and still the only thing that works there.
 */
export function useClipboard(resetAfterMs = 1800): Clipboard {
  const [state, setState] = useState<ClipboardState>('idle')
  const timer = useRef<number | null>(null)

  useEffect(
    () => () => {
      if (timer.current !== null) {
        window.clearTimeout(timer.current)
      }
    },
    [],
  )

  const settle = useCallback(
    (next: ClipboardState) => {
      setState(next)
      if (timer.current !== null) {
        window.clearTimeout(timer.current)
      }
      timer.current = window.setTimeout(() => setState('idle'), resetAfterMs)
    },
    [resetAfterMs],
  )

  const copy = useCallback(
    async (text: string) => {
      const done = await writeToClipboard(text)
      settle(done ? 'copied' : 'failed')
      return done
    },
    [settle],
  )

  return {copy, state}
}

async function writeToClipboard(text: string): Promise<boolean> {
  if (window.isSecureContext && navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // Permission refused, or the document was not focused. Fall through rather than
      // reporting success for something that did not happen.
    }
  }
  return legacyCopy(text)
}

function legacyCopy(text: string): boolean {
  const field = document.createElement('textarea')
  field.value = text
  // Off-screen but focusable: `display: none` cannot be selected, and a visible element
  // would make the page jump on a phone as the keyboard tries to open.
  field.setAttribute('readonly', '')
  field.style.position = 'fixed'
  field.style.top = '-1000px'
  field.style.opacity = '0'
  document.body.appendChild(field)
  try {
    field.select()
    field.setSelectionRange(0, text.length)
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    document.body.removeChild(field)
  }
}
