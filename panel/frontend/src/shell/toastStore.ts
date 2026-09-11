/**
 * The notice queue.
 *
 * A module-level store rather than a context, because most of the things that need to
 * raise a notice are not components: the clipboard helper, an upload that failed halfway,
 * a terminal session the node ended. Making them all reach for a hook would mean
 * threading a callback through every one of them.
 *
 * Server-sent notices come the other way: `flash.success` and `flash.error` are shared
 * props, and `Toaster` turns them into entries here so there is one place a notice is
 * rendered rather than a banner on some pages and a toast on others.
 */
export type ToastTone = 'success' | 'error' | 'info'

export interface Toast {
  id: number
  tone: ToastTone
  message: string
  /** Milliseconds on screen. Errors stay until dismissed. */
  durationMs: number
}

export interface ToastRequest {
  tone?: ToastTone
  message: string
  durationMs?: number
}

const DEFAULT_MS: Record<ToastTone, number> = {
  // Long enough to read a sentence, short enough not to sit over the bottom navigation.
  success: 4000,
  info: 5000,
  // Zero means "until dismissed": a failure the customer did not see is a failure they
  // will report as the panel doing nothing.
  error: 0,
}

let entries: Toast[] = []
let nextId = 1
const listeners = new Set<() => void>()

function publish(): void {
  for (const listener of listeners) {
    listener()
  }
}

export function subscribeToToasts(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function currentToasts(): Toast[] {
  return entries
}

/** Adds a notice and returns its id, so a caller can withdraw it early. */
export function showToast(request: ToastRequest): number {
  const tone = request.tone ?? 'info'
  const entry: Toast = {
    id: nextId++,
    tone,
    message: request.message,
    durationMs: request.durationMs ?? DEFAULT_MS[tone],
  }
  // Newest last, and never more than four: a stack taller than that covers the content
  // the customer is trying to act on.
  entries = [...entries, entry].slice(-4)
  publish()
  return entry.id
}

export function dismissToast(id: number): void {
  const remaining = entries.filter((entry) => entry.id !== id)
  if (remaining.length === entries.length) {
    return
  }
  entries = remaining
  publish()
}

/** Shorthands, so a call site reads as what happened rather than as configuration. */
export const toast = {
  success: (message: string) => showToast({tone: 'success', message}),
  error: (message: string) => showToast({tone: 'error', message}),
  info: (message: string) => showToast({tone: 'info', message}),
}
