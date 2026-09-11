import type {ReactNode} from 'react'

import {cx} from './cx'
import {Icon} from './Icon'
import {useDialog} from './useDialog'

/**
 * A panel that slides in from the edge and holds a list.
 *
 * `Modal` is for a decision; this is for a set of choices - the navigation on a phone,
 * the organization switcher, the row actions that do not fit in a swipe. Full height,
 * because the things it holds are lists and a list wants the long axis of the screen.
 *
 * Left by default: it is opened from the menu button in the top-left corner, and a panel
 * that appears somewhere other than where the finger pressed is disorienting.
 */
export interface DrawerProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  side?: 'left' | 'right'
  /** The default is comfortable on a 375px screen without covering it completely. */
  widthClassName?: string
  footer?: ReactNode
  children: ReactNode
}

export function Drawer({
  open,
  onClose,
  title,
  side = 'left',
  widthClassName = 'w-[19rem] max-w-[86vw]',
  footer,
  children,
}: DrawerProps) {
  const dialog = useDialog(open, onClose, true)

  return (
    <dialog
      ref={dialog.ref}
      onCancel={dialog.onCancel}
      onClick={dialog.onClick}
      onClose={dialog.onClose}
      aria-label={typeof title === 'string' ? title : undefined}
      className={cx(
        'm-0 max-h-none max-w-none border-0 bg-transparent p-0',
        // `w-full` rather than `w-dvw`: a modal dialog is fixed to the viewport, so a
        // percentage resolves against it without the classic scrollbar `dvw` includes.
        'h-dvh w-full',
        /*
         * Behind `open:`, and this is not cosmetic. The UA hides a closed dialog with
         * `dialog:not([open]) {display: none}`, but that rule is in the user-agent
         * origin, which every author declaration outranks whatever its specificity. A
         * plain `flex` here therefore leaves the drawer on screen permanently - the
         * navigation, the account menu and the row actions all stacked over the page.
         */
        'open:flex',
        side === 'left' ? 'justify-start' : 'justify-end',
      )}
    >
      <div
        data-panel={side}
        className={cx(
          'flex h-dvh flex-col bg-white shadow-xl dark:bg-ink-900',
          widthClassName,
        )}
      >
        <header className="flex items-center gap-2 border-b border-ink-200 px-4 py-3 pt-safe dark:border-ink-800">
          <h2 className="min-w-0 flex-1 truncate text-base font-semibold text-ink-900 dark:text-ink-100">
            {title}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="-mr-2 flex touch-target items-center justify-center rounded-lg text-ink-500 hover:bg-ink-100 dark:hover:bg-ink-800"
          >
            <Icon name="close" />
          </button>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-2 py-3">
          {children}
        </div>

        {footer ? (
          <footer className="border-t border-ink-200 px-4 py-3 pb-safe dark:border-ink-800">
            {footer}
          </footer>
        ) : null}
      </div>
    </dialog>
  )
}
