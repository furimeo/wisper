import type {ReactNode} from 'react'

import {cx} from './cx'

/**
 * Label, control, hint and message, in the order a screen reader announces them.
 *
 * Every control in the shell renders one of these when it is given a `label`, so the
 * spacing and the way a validation message appears are decided once. The message is the
 * one the server sent, keyed by the input's `name` - see `useFormFields` - so the wording
 * a customer reads is the wording the panel logged.
 */
export interface FieldProps {
  label?: ReactNode
  /** The id of the control this labels. */
  htmlFor?: string
  /** Shown under the control while it is valid. */
  hint?: ReactNode
  /** Shown instead of the hint, in the failure colour, when the server rejected it. */
  error?: string
  /** Draws the "required" marker. The server still decides. */
  required?: boolean
  children: ReactNode
  className?: string
}

export function Field({
  label,
  htmlFor,
  hint,
  error,
  required,
  children,
  className,
}: FieldProps) {
  // Only worth an id when there is a control to point back at it.
  const messageId = htmlFor ? `${htmlFor}-error` : undefined
  const hintId = htmlFor ? `${htmlFor}-hint` : undefined

  return (
    <div className={cx('flex flex-col gap-1.5', className)}>
      {label ? (
        <label
          htmlFor={htmlFor}
          className="text-sm font-medium text-ink-700 dark:text-ink-300"
        >
          {label}
          {required ? (
            <span className="ml-1 text-failed" aria-hidden="true">
              *
            </span>
          ) : null}
        </label>
      ) : null}

      {children}

      {error ? (
        <p id={messageId} role="alert" className="text-sm text-failed">
          {error}
        </p>
      ) : hint ? (
        <p id={hintId} className="text-sm text-ink-500 dark:text-ink-400">
          {hint}
        </p>
      ) : null}
    </div>
  )
}

/** The id a control hands to `aria-describedby` so the message is read with it. */
export function describedBy(id: string, error?: string, hint?: ReactNode): string | undefined {
  if (error) {
    return `${id}-error`
  }
  return hint ? `${id}-hint` : undefined
}
