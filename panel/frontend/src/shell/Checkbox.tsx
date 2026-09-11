import type {InputHTMLAttributes, ReactNode} from 'react'
import {useId} from 'react'

import {cx} from './cx'

/**
 * A checkbox with its label as part of the hit area.
 *
 * The whole row is the target, not the 16px box: a checkbox a thumb misses twice is
 * worse than no checkbox. The native input is kept - `accent-color` styles it in every
 * browser the panel supports - so the keyboard, the screen reader and form submission all
 * behave without a line of code here.
 */
export interface CheckboxProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'size'> {
  label: ReactNode
  hint?: ReactNode
  error?: string
  className?: string
}

export function Checkbox({label, hint, error, className, id, ...rest}: CheckboxProps) {
  const generated = useId()
  const controlId = id ?? generated
  const hintId = hint || error ? `${controlId}-hint` : undefined

  return (
    <div className={cx('flex flex-col gap-1', className)}>
      <label
        htmlFor={controlId}
        className={cx(
          'flex touch-target cursor-pointer items-center gap-3 rounded-lg py-1.5',
          rest.disabled ? 'cursor-not-allowed opacity-60' : '',
        )}
      >
        <input
          {...rest}
          id={controlId}
          type="checkbox"
          aria-describedby={hintId}
          aria-invalid={error ? true : undefined}
          className={cx(
            'size-5 shrink-0 rounded border-ink-400 accent-accent-600',
            'dark:border-ink-600',
            error ? 'outline-2 outline-offset-2 outline-failed' : '',
          )}
        />
        <span className="text-sm text-ink-800 dark:text-ink-200">{label}</span>
      </label>

      {error ? (
        <p id={hintId} role="alert" className="pl-8 text-sm text-failed">
          {error}
        </p>
      ) : hint ? (
        <p id={hintId} className="pl-8 text-sm text-ink-500 dark:text-ink-400">
          {hint}
        </p>
      ) : null}
    </div>
  )
}
