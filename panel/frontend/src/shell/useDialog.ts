import type {MouseEvent, SyntheticEvent} from 'react'
import {useCallback, useEffect, useRef} from 'react'

/**
 * The wiring behind `Modal` and `Drawer`.
 *
 * Both are a native `<dialog>` opened with `showModal()`, which is the reason there is no
 * focus-trap code in this repository: the top layer already takes focus, keeps Tab inside
 * the dialog, returns focus to whatever opened it, and answers Escape. Every hand-rolled
 * React modal reimplements those four things and gets at least one of them wrong on a
 * screen reader.
 *
 * What the platform does not do is close on a backdrop click, and it does not stop the
 * page behind from scrolling on iOS. Those are the two jobs here.
 */
export interface DialogHandles {
  ref: (element: HTMLDialogElement | null) => void
  /** Escape, and any other UA-initiated cancel. */
  onCancel: (event: SyntheticEvent<HTMLDialogElement>) => void
  /** A click that landed on the backdrop rather than on the panel. */
  onClick: (event: MouseEvent<HTMLDialogElement>) => void
  /** The element closed itself - a `<form method="dialog">`, or the UA. */
  onClose: (event: SyntheticEvent<HTMLDialogElement>) => void
}

/**
 * Whether this handler is looking at its own element's event.
 *
 * `cancel` and `close` do not bubble in the DOM, but React re-dispatches them through its
 * own tree anyway. The organization switcher's drawer is a React child of the navigation
 * drawer, so without this guard dismissing the switcher also closes the navigation behind
 * it - and a confirmation inside any dialog would take the dialog with it.
 */
function isOwnEvent(event: SyntheticEvent<HTMLDialogElement>): boolean {
  return event.target === event.currentTarget
}

export function useDialog(
  open: boolean,
  requestClose: () => void,
  dismissible = true,
): DialogHandles {
  const element = useRef<HTMLDialogElement | null>(null)
  // Read inside handlers that outlive the render they were created in.
  const closer = useRef(requestClose)
  closer.current = requestClose
  const dismissibleRef = useRef(dismissible)
  dismissibleRef.current = dismissible
  const openRef = useRef(open)
  openRef.current = open

  const ref = useCallback((node: HTMLDialogElement | null) => {
    element.current = node
  }, [])

  useEffect(() => {
    const node = element.current
    if (!node) {
      return
    }
    if (open && !node.open) {
      node.showModal()
    } else if (!open && node.open) {
      node.close()
    }
  }, [open])

  useEffect(() => {
    if (!open) {
      return
    }
    /*
     * showModal() makes the page behind inert, but on iOS Safari the body still scrolls
     * under the sheet and the customer loses their place in a long list. Restoring the
     * previous value rather than clearing it keeps a dialog opened on top of another -
     * a confirmation over a form - from unlocking the page when only the inner one
     * closes.
     */
    const root = document.documentElement
    const previous = root.style.overflow
    root.style.overflow = 'hidden'
    return () => {
      root.style.overflow = previous
    }
  }, [open])

  const onCancel = useCallback((event: SyntheticEvent<HTMLDialogElement>) => {
    if (!isOwnEvent(event)) {
      return
    }
    // Always prevented: React state decides whether the dialog is open, and letting the
    // element close itself leaves the two disagreeing until the next render.
    event.preventDefault()
    if (dismissibleRef.current) {
      closer.current()
    }
  }, [])

  const onClick = useCallback((event: MouseEvent<HTMLDialogElement>) => {
    // The backdrop is part of the dialog's own box, so a click on it reports the dialog
    // as the target. A click on anything inside reports that child.
    if (event.target === event.currentTarget && dismissibleRef.current) {
      closer.current()
    }
  }, [])

  const onClose = useCallback((event: SyntheticEvent<HTMLDialogElement>) => {
    if (!isOwnEvent(event)) {
      return
    }
    // Also fires for the close this hook performed itself, in which case React already
    // knows and telling it again would be a second state change for the same event.
    if (openRef.current) {
      closer.current()
    }
  }, [])

  return {ref, onCancel, onClick, onClose}
}
