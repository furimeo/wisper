import type {ReactNode} from 'react'
import {useEffect, useLayoutEffect, useRef, useState} from 'react'

import {Drawer, cx, useIsWide} from '@/shell'

import {FileActionIcon} from './FileActionIcon'
import type {FileActionKind, FileActionState} from './fileActions'

/** Where a right-click landed, in viewport coordinates. */
export interface MenuAnchor {
  x: number
  y: number
}

/**
 * The same operations as the toolbar, wherever the pointer or the thumb asked for them.
 *
 * Two presentations of one list. A right-click on a desktop opens a menu at the pointer,
 * which is the interaction every file manager has had for thirty years and the reason a
 * customer tries it before they read anything. A phone has no right-click, so the row's
 * overflow button and the toolbar's "Actions" open the same list as a sheet - and because
 * it is the same list, the two cannot drift into offering different things.
 *
 * Disabled entries are shown, greyed, with the sentence saying why underneath. In a menu
 * that costs a line of text and buys the answer to "where did Extract go?", which is a
 * question a menu that hides its unavailable items cannot answer.
 *
 * The menu is deliberately not a `<dialog>`. It must not take the top layer or trap focus:
 * on a desktop a context menu that made the page inert would stop the customer scrolling
 * the list they opened it over.
 */
export function FileActionMenu({
  open,
  anchor,
  title,
  states,
  onAction,
  onClose,
}: {
  open: boolean
  /** Null opens the sheet instead: the toolbar's button, and every phone. */
  anchor: MenuAnchor | null
  title: string
  states: FileActionState[]
  onAction: (kind: FileActionKind) => void
  onClose: () => void
}) {
  const wide = useIsWide()
  const items = states.filter((state) => state.kind !== 'refresh')

  if (!wide || anchor === null) {
    return (
      <Drawer open={open} onClose={onClose} side="right" title={title}>
        <ul className="flex flex-col">
          {items.map((state) => (
            <li key={state.kind}>
              <MenuItem
                state={state}
                onSelect={() => {
                  onClose()
                  onAction(state.kind)
                }}
                touch
              />
            </li>
          ))}
        </ul>
      </Drawer>
    )
  }

  return (
    <PointerMenu open={open} anchor={anchor} title={title} onClose={onClose}>
      {items.map((state) => (
        <MenuItem
          key={state.kind}
          state={state}
          onSelect={() => {
            onClose()
            onAction(state.kind)
          }}
        />
      ))}
    </PointerMenu>
  )
}

/** The desktop half: a small panel pinned to the pointer and clamped to the viewport. */
function PointerMenu({
  open,
  anchor,
  title,
  onClose,
  children,
}: {
  open: boolean
  anchor: MenuAnchor
  title: string
  onClose: () => void
  children: ReactNode
}) {
  const panel = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<MenuAnchor>(anchor)

  /*
   * Measured after layout, before paint. A menu opened near the bottom of the window has
   * to move up by its own height, and its own height is not known until it has rendered -
   * doing this in an effect instead would show it in the wrong place for one frame.
   */
  useLayoutEffect(() => {
    const node = panel.current
    if (!open || !node) {
      return
    }
    const margin = 8
    const box = node.getBoundingClientRect()
    setPosition({
      x: Math.max(margin, Math.min(anchor.x, window.innerWidth - box.width - margin)),
      y: Math.max(margin, Math.min(anchor.y, window.innerHeight - box.height - margin)),
    })
  }, [open, anchor])

  useEffect(() => {
    if (!open) {
      return
    }
    const dismiss = () => onClose()
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation()
        onClose()
      }
    }
    const onPointerDown = (event: PointerEvent) => {
      if (!panel.current?.contains(event.target as Node)) {
        onClose()
      }
    }
    document.addEventListener('pointerdown', onPointerDown, true)
    document.addEventListener('keydown', onKeyDown, true)
    // Scrolling the list underneath would leave the menu pointing at a different row.
    window.addEventListener('scroll', dismiss, true)
    window.addEventListener('resize', dismiss)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown, true)
      document.removeEventListener('keydown', onKeyDown, true)
      window.removeEventListener('scroll', dismiss, true)
      window.removeEventListener('resize', dismiss)
    }
  }, [open, onClose])

  useEffect(() => {
    if (open) {
      // The first enabled entry, so the menu can be driven with Tab and Enter from here.
      panel.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
    }
  }, [open])

  if (!open) {
    return null
  }

  return (
    <div
      ref={panel}
      role="menu"
      aria-label={title}
      style={{left: position.x, top: position.y}}
      className={cx(
        'fixed z-50 w-64 overflow-hidden rounded-xl border py-1 shadow-lg',
        'border-ink-200 bg-white dark:border-ink-800 dark:bg-ink-900',
      )}
    >
      <p className="truncate px-3 py-1.5 text-xs font-medium text-ink-500 dark:text-ink-400">
        {title}
      </p>
      {children}
    </div>
  )
}

function MenuItem({
  state,
  onSelect,
  touch,
}: {
  state: FileActionState
  onSelect: () => void
  /** A sheet row: 44px minimum, and the reason on its own line. */
  touch?: boolean
}) {
  const disabled = state.disabledReason !== null
  return (
    <button
      type="button"
      role="menuitem"
      onClick={onSelect}
      disabled={disabled}
      title={state.disabledReason ?? undefined}
      className={cx(
        'flex w-full items-start gap-3 px-3 text-left',
        touch ? 'touch-target rounded-lg py-2.5' : 'py-2',
        disabled
          ? 'cursor-not-allowed text-ink-400 dark:text-ink-600'
          : state.tone === 'danger'
            ? 'text-failed hover:bg-failed/10'
            : 'text-ink-800 hover:bg-ink-100 dark:text-ink-100 dark:hover:bg-ink-800',
      )}
    >
      <FileActionIcon kind={state.kind} className="mt-0.5" />
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-medium">{state.label}</span>
        {state.disabledReason && touch ? (
          <span className="mt-0.5 block text-xs">{state.disabledReason}</span>
        ) : null}
      </span>
    </button>
  )
}
