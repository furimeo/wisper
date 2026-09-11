import type {ReactNode} from 'react'
import {useId} from 'react'

import {cx} from './cx'
import {Icon} from './Icon'
import {useDialog} from './useDialog'

/**
 * A dialog: a bottom sheet on a phone, a centred card from `md` upward.
 *
 * The two are one component because they are one thing - a decision the customer has to
 * make before carrying on - and because a centred 400px box on a 375px screen puts its
 * buttons in the middle of the display, which is the part of a phone a thumb reaches
 * last. A sheet puts them where the thumb already is.
 *
 * `dismissible={false}` is for a dialog in the middle of doing something: a restore that
 * has started, an upload that would be orphaned. It stops Escape and the backdrop, not
 * the close button, because a dialog with no way out at all is a hang.
 */
export type ModalSize = 'sm' | 'md' | 'lg'

const SIZES: Record<ModalSize, string> = {
  sm: 'md:max-w-sm',
  md: 'md:max-w-lg',
  lg: 'md:max-w-2xl',
}

export interface ModalProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  size?: ModalSize
  dismissible?: boolean
  /** The action row. Buttons stack full-width on a phone. */
  footer?: ReactNode
  children?: ReactNode
}

export function Modal({
  open,
  onClose,
  title,
  description,
  size = 'md',
  dismissible = true,
  footer,
  children,
}: ModalProps) {
  const dialog = useDialog(open, onClose, dismissible)
  const titleId = useId()

  return (
    <dialog
      ref={dialog.ref}
      onCancel={dialog.onCancel}
      onClick={dialog.onClick}
      onClose={dialog.onClose}
      aria-labelledby={titleId}
      className={cx(
        // The UA gives a dialog a centred box with its own max sizes; all of that has to
        // go before it can be a full-screen overlay.
        'm-0 max-h-none max-w-none border-0 bg-transparent p-0',
        // `w-full` rather than `w-dvw`: a modal dialog is fixed to the viewport, so a
        // percentage resolves against it without the classic scrollbar `dvw` includes.
        'h-dvh w-full',
        /*
         * `open:` is load-bearing. `dialog:not([open]) {display: none}` lives in the
         * user-agent origin, and every author declaration outranks that origin however
         * specific it is - so an unconditional `flex` leaves the sheet covering the page
         * from the moment it mounts, whether or not anything opened it.
         */
        'open:flex items-end justify-center md:items-center',
      )}
    >
      <div
        data-panel="sheet"
        className={cx(
          'flex max-h-[92dvh] w-full flex-col overflow-hidden',
          'rounded-t-2xl bg-white shadow-xl dark:bg-ink-900',
          'md:rounded-2xl',
          SIZES[size],
        )}
      >
        <header className="flex items-start gap-3 border-b border-ink-200 px-4 py-3.5 dark:border-ink-800">
          <div className="min-w-0 flex-1">
            <h2
              id={titleId}
              className="text-base font-semibold text-ink-900 dark:text-ink-100"
            >
              {title}
            </h2>
            {description ? (
              <p className="mt-1 text-sm text-ink-600 dark:text-ink-400">{description}</p>
            ) : null}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            /*
             * -mt-2.5 is (44 - 24) / 2. The header is items-start so a title with a
             * description underneath keeps the button beside the title rather than
             * floating to the middle of the pair - but a 44px touch target top-aligned
             * against a 24px line sits 10px low, which is plainly visible on a one-line
             * title. Pulling it up by half the difference centres it on that line.
             */
            className="-mr-2 -mt-2.5 flex touch-target items-center justify-center rounded-lg text-ink-500 hover:bg-ink-100 dark:hover:bg-ink-800"
          >
            <Icon name="close" />
          </button>
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">{children}</div>

        {footer ? (
          <footer className="flex flex-col-reverse gap-2 border-t border-ink-200 px-4 py-3 [--safe-bottom-base:0.75rem] pb-safe sm:flex-row sm:justify-end dark:border-ink-800">
            {footer}
          </footer>
        ) : null}
      </div>
    </dialog>
  )
}
