import type {PointerEvent as ReactPointerEvent, ReactNode} from 'react'
import {useCallback, useEffect, useRef, useState} from 'react'

import {cx} from './cx'

/**
 * A list row whose actions are revealed by dragging it to the left.
 *
 * This is the mobile half of `DataList`. It is a shortcut, never the only way in: every
 * action here is also behind the row's overflow button, because a swipe cannot be
 * performed with a keyboard, is invisible to a screen reader, and is not discoverable by
 * anybody who has not been shown it.
 *
 * The gesture has to share the screen with vertical scrolling, which is the part that is
 * usually got wrong. `touch-action: pan-y` leaves the browser in charge of the vertical
 * axis, and the drag only engages once the pointer has moved further horizontally than
 * vertically - so a thumb flicking down a list never drags a row open by accident.
 */
export interface SwipeAction {
  label: string
  icon?: ReactNode
  tone?: 'neutral' | 'danger'
  onSelect: () => void
}

export interface SwipeRowProps {
  actions: SwipeAction[]
  /** Held by the list, so only one row is open at a time. */
  open: boolean
  onOpenChange: (open: boolean) => void
  children: ReactNode
  className?: string
}

/** Past this much movement the gesture is a drag rather than a tap or a scroll. */
const ENGAGE_PX = 10

export function SwipeRow({actions, open, onOpenChange, children, className}: SwipeRowProps) {
  const drawer = useRef<HTMLDivElement | null>(null)
  const [width, setWidth] = useState(0)
  const [offset, setOffset] = useState(0)
  const [dragging, setDragging] = useState(false)

  const gesture = useRef<{
    pointerId: number
    startX: number
    startY: number
    startOffset: number
    engaged: boolean
    abandoned: boolean
  } | null>(null)

  // The action strip sizes itself to its buttons, so how far the row travels is measured
  // rather than guessed - two actions and four actions are different distances.
  useEffect(() => {
    const node = drawer.current
    if (!node) {
      return
    }
    const observer = new ResizeObserver(() => setWidth(node.offsetWidth))
    observer.observe(node)
    setWidth(node.offsetWidth)
    return () => observer.disconnect()
  }, [actions.length])

  useEffect(() => {
    if (!dragging) {
      setOffset(open ? -width : 0)
    }
  }, [open, width, dragging])

  const onPointerDown = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      if (actions.length === 0 || event.pointerType === 'mouse') {
        // A mouse has the overflow button and a hover target; dragging with one is a
        // gesture nobody tries, and capturing the pointer would break text selection.
        return
      }
      gesture.current = {
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
        startOffset: offset,
        engaged: false,
        abandoned: false,
      }
    },
    [actions.length, offset],
  )

  const onPointerMove = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      const current = gesture.current
      if (!current || current.pointerId !== event.pointerId || current.abandoned) {
        return
      }
      const dx = event.clientX - current.startX
      const dy = event.clientY - current.startY

      if (!current.engaged) {
        if (Math.abs(dy) > ENGAGE_PX && Math.abs(dy) > Math.abs(dx)) {
          // Scrolling the list. Leave it alone for the rest of this pointer.
          current.abandoned = true
          return
        }
        if (Math.abs(dx) < ENGAGE_PX || Math.abs(dx) <= Math.abs(dy)) {
          return
        }
        current.engaged = true
        setDragging(true)
        event.currentTarget.setPointerCapture(event.pointerId)
      }

      setOffset(Math.min(0, Math.max(-width, current.startOffset + dx)))
    },
    [width],
  )

  const finish = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      const current = gesture.current
      gesture.current = null
      if (!current?.engaged) {
        return
      }
      if (event.currentTarget.hasPointerCapture(event.pointerId)) {
        event.currentTarget.releasePointerCapture(event.pointerId)
      }
      setDragging(false)
      const shouldOpen = width > 0 && offset < -width / 2
      setOffset(shouldOpen ? -width : 0)
      onOpenChange(shouldOpen)
    },
    [offset, width, onOpenChange],
  )

  return (
    <div className={cx('relative overflow-hidden', className)}>
      <div
        ref={drawer}
        aria-hidden="true"
        className="absolute inset-y-0 right-0 flex items-stretch"
      >
        {actions.map((action) => (
          <button
            key={action.label}
            type="button"
            // Hidden from assistive technology on purpose: the same action is a real,
            // focusable control in the row's overflow menu.
            tabIndex={-1}
            onClick={() => {
              onOpenChange(false)
              action.onSelect()
            }}
            className={cx(
              'flex w-20 flex-col items-center justify-center gap-1 px-2 text-xs font-medium',
              action.tone === 'danger'
                ? 'bg-failed text-white'
                : 'bg-ink-200 text-ink-800 dark:bg-ink-700 dark:text-ink-100',
            )}
          >
            {action.icon}
            <span className="truncate">{action.label}</span>
          </button>
        ))}
      </div>

      <div
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={finish}
        onPointerCancel={finish}
        style={{transform: `translate3d(${offset}px, 0, 0)`, touchAction: 'pan-y'}}
        className={cx(
          'relative bg-white dark:bg-ink-900',
          dragging ? '' : 'transition-transform duration-200',
        )}
      >
        {children}
      </div>
    </div>
  )
}
