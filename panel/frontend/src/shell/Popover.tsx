import type {ReactNode} from 'react'
import {useEffect, useRef} from 'react'

import {cx} from './cx'

/**
 * A small panel anchored under the control that opened it.
 *
 * <p>`Drawer` is the touch answer: full height, slides from an edge, big targets. On a
 * desktop that is the wrong shape for a five-item menu - a panel the height of the window
 * arriving from the side reads as "you have navigated somewhere", not as "here are your
 * account links", and the motion is large enough to be distracting when it fires from a
 * corner of the screen the eye is not on.
 *
 * <p>Not a `<dialog>`: this does not need the top layer, a backdrop, or focus trapping.
 * It is a menu, it closes when you look away, and it must not stop the page behind it
 * scrolling.
 */
export interface PopoverProps {
  open: boolean
  onClose: () => void
  /** Which edge of the anchor it lines up with. `end` is the right edge, for a top bar. */
  align?: 'start' | 'end'
  widthClassName?: string
  children: ReactNode
}

export function Popover({
  open,
  onClose,
  align = 'end',
  widthClassName = 'w-72',
  children,
}: PopoverProps) {
  const panel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) {
      return
    }
    /*
     * `pointerdown` rather than `click`: a click fires after the button's own handler has
     * already toggled the state, so pressing the anchor a second time would close and
     * immediately reopen. Pointerdown lands first, and the anchor is excluded because it
     * is outside this element.
     */
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node
      if (panel.current && !panel.current.contains(target)
          && !panel.current.parentElement?.contains(target)) {
        onClose()
      }
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose()
      }
    }
    document.addEventListener('pointerdown', onPointerDown, true)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown, true)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open, onClose])

  if (!open) {
    return null
  }

  return (
    <div
      ref={panel}
      data-panel="popover"
      className={cx(
        'absolute top-full z-40 mt-2 overflow-hidden rounded-xl border shadow-lg',
        'border-ink-200 bg-white dark:border-ink-800 dark:bg-ink-900',
        align === 'end' ? 'right-0 origin-top-right' : 'left-0 origin-top-left',
        widthClassName,
      )}
    >
      {children}
    </div>
  )
}
