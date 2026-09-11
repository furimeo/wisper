import type {ReactNode, SelectHTMLAttributes} from 'react'
import {useId} from 'react'

import {cx} from './cx'
import {Field, describedBy} from './Field'
import {CONTROL_CLASSES, controlBorder} from './Input'

/**
 * A native `<select>`.
 *
 * Native rather than a listbox built out of divs, because on a phone the native control
 * opens the platform's own wheel or sheet - which is reachable one-handed, respects the
 * system font size, and needs no code here to be usable by a screen reader. The panel's
 * selects are all short, closed vocabularies out of `pages.md` §4, so nothing is lost.
 */
export interface SelectOption {
  value: string
  label: string
  disabled?: boolean
}

export interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  label?: ReactNode
  hint?: ReactNode
  error?: string
  /** Either pass options, or pass `<option>` children directly. */
  options?: SelectOption[]
  /** Prepends a disabled option, for a value the customer must actually choose. */
  placeholder?: string
  fieldClassName?: string
}

export function Select({
  label,
  hint,
  error,
  options,
  placeholder,
  fieldClassName,
  className,
  children,
  id,
  ...rest
}: SelectProps) {
  const generated = useId()
  const controlId = id ?? generated
  const invalid = Boolean(error)

  const control = (
    <div className="relative">
      <select
        {...rest}
        id={controlId}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy(controlId, error, hint)}
        className={cx(
          CONTROL_CLASSES,
          controlBorder(invalid),
          // `appearance-none` drops the platform arrow so the one below can sit at a
          // predictable place in both themes; the padding leaves room for it.
          'min-h-11 appearance-none pr-10',
          className,
        )}
      >
        {placeholder ? (
          <option value="" disabled>
            {placeholder}
          </option>
        ) : null}
        {options?.map((option) => (
          <option key={option.value} value={option.value} disabled={option.disabled}>
            {option.label}
          </option>
        ))}
        {children}
      </select>
      <svg
        className="pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 text-ink-500"
        viewBox="0 0 20 20"
        fill="none"
        aria-hidden="true"
      >
        <path
          d="m5.5 7.5 4.5 4.5 4.5-4.5"
          stroke="currentColor"
          strokeWidth="1.6"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    </div>
  )

  if (!label && !hint && !error) {
    return control
  }
  return (
    <Field
      label={label}
      htmlFor={controlId}
      hint={hint}
      error={error}
      required={rest.required}
      className={fieldClassName}
    >
      {control}
    </Field>
  )
}
