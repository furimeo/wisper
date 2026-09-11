/**
 * The confirmation queue.
 *
 * `askConfirmation()` returns a promise so a call site reads as one sentence:
 *
 * ```ts
 * if (!(await askConfirmation({title: 'Delete this volume?', tone: 'danger'}))) return
 * router.post(`/services/${id}/volumes/delete`, {volumeId})
 * ```
 *
 * `requireText` is the "type the node's name to confirm" pattern, which the node delete
 * screen is required to use - it is the difference between a mis-tap and a decision, and
 * the panel asks for it wherever an action destroys something a customer cannot get back.
 *
 * If no host is mounted - a page rendered without the app chrome, which is only the
 * sign-in screens - this falls back to the browser's own dialog rather than returning a
 * promise that never settles.
 */
export type ConfirmTone = 'danger' | 'normal'

export interface ConfirmRequest {
  title: string
  /** What will happen. Say the consequence, not "are you sure". */
  body?: string
  confirmLabel?: string
  cancelLabel?: string
  tone?: ConfirmTone
  /** The exact string the customer has to type before the button becomes usable. */
  requireText?: string
  /** The label above that input. */
  requireTextLabel?: string
}

export interface PendingConfirmation extends ConfirmRequest {
  id: number
  settle: (confirmed: boolean) => void
}

let pending: PendingConfirmation | null = null
let nextId = 1
const listeners = new Set<() => void>()
let hosts = 0

function publish(): void {
  for (const listener of listeners) {
    listener()
  }
}

export function subscribeToConfirmations(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function currentConfirmation(): PendingConfirmation | null {
  return pending
}

/** Called by `ConfirmHost` while it is mounted. */
export function registerConfirmHost(): () => void {
  hosts += 1
  return () => {
    hosts -= 1
  }
}

export function askConfirmation(request: ConfirmRequest): Promise<boolean> {
  if (hosts === 0) {
    // No chrome on this page. The browser's dialog is worse-looking and entirely real,
    // which beats an action that silently never happens.
    return Promise.resolve(window.confirm(`${request.title}\n\n${request.body ?? ''}`.trim()))
  }
  if (pending) {
    // One question at a time. A second one arriving means two controls were pressed in
    // the same tick, and answering the first is the honest resolution.
    pending.settle(false)
  }
  return new Promise<boolean>((resolve) => {
    pending = {
      ...request,
      id: nextId++,
      settle: (confirmed) => {
        pending = null
        publish()
        resolve(confirmed)
      },
    }
    publish()
  })
}
