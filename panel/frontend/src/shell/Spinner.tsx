import {cx} from './cx'

/**
 * The one busy indicator in the panel.
 *
 * Drawn with `currentColor` so it inherits from whatever it sits in - a primary button,
 * a ghost button, a row - instead of needing a colour prop that then has to be kept in
 * step with the button variants.
 */
export function Spinner({className, label}: {className?: string; label?: string}) {
  return (
    <svg
      className={cx('size-4 shrink-0 animate-spin', className)}
      viewBox="0 0 24 24"
      fill="none"
      role={label ? 'status' : 'presentation'}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2.5" opacity="0.25" />
      <path
        d="M21 12a9 9 0 0 0-9-9"
        stroke="currentColor"
        strokeWidth="2.5"
        strokeLinecap="round"
      />
    </svg>
  )
}
