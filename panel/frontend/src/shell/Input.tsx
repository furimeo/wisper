import type {InputHTMLAttributes, ReactNode, RefObject} from 'react'
import {useEffect, useId, useRef, useState} from 'react'

import {cx} from './cx'
import {Field, describedBy} from './Field'

/**
 * A single-line text control.
 *
 * `text-base` on a phone is not a style choice: iOS zooms the whole page when a focused
 * input's text is under 16px and does not zoom back out, which on a form with six fields
 * leaves the customer panning sideways to find the next one. It drops to `text-sm` from
 * `md` upward, where no such thing happens.
 */
export interface InputProps
  // `size` is the ancient character-count attribute, and `prefix` is RDFa's, typed as a
  // string. Both are dropped so the names can mean what they mean everywhere else in this
  // shell; nothing in the panel has a use for either of the originals.
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size' | 'prefix'> {
  label?: ReactNode
  hint?: ReactNode
  error?: string
  /** Rendered inside the control, before the text - a currency mark, a protocol. */
  prefix?: ReactNode
  /** Rendered inside the control, after the text - a unit, a copy button. */
  suffix?: ReactNode
  fieldClassName?: string
}

/** The border, ring and background every text-like control in the shell shares. */
export const CONTROL_CLASSES =
  'w-full rounded-lg border bg-white px-3 text-base text-ink-900 ' +
  'placeholder:text-ink-400 md:text-sm ' +
  'dark:bg-ink-900 dark:text-ink-50 dark:placeholder:text-ink-500 ' +
  'disabled:cursor-not-allowed disabled:bg-ink-100 dark:disabled:bg-ink-800'

/** Neutral and failed borders, kept together so they cannot drift apart. */
export function controlBorder(invalid: boolean): string {
  return invalid
    ? 'border-failed focus-visible:outline-failed'
    : 'border-ink-300 dark:border-ink-700'
}

/** The gutter an adornment sits in, and the gap between it and the text. */
const PREFIX_INSET = 12
const SUFFIX_INSET = 8
const ADORNMENT_GAP = 8

/**
 * How wide an adornment actually is, so the text never runs underneath it.
 *
 * A fixed padding is enough for a currency mark and wrong for `https://` - which is this
 * component's own documented example - and hopelessly wrong for a suffix that is a whole
 * copy button. Measuring is the only version of this that cannot be broken by the string
 * the caller passes.
 */
function useAdornmentWidth(present: boolean): [RefObject<HTMLSpanElement | null>, number] {
  const element = useRef<HTMLSpanElement | null>(null)
  const [width, setWidth] = useState(0)

  useEffect(() => {
    const node = element.current
    if (!present || !node) {
      setWidth(0)
      return
    }
    const observer = new ResizeObserver(() => setWidth(node.offsetWidth))
    observer.observe(node)
    setWidth(node.offsetWidth)
    return () => observer.disconnect()
  }, [present])

  return [element, width]
}

export function Input({
  label,
  hint,
  error,
  prefix,
  suffix,
  fieldClassName,
  className,
  style,
  id,
  ...rest
}: InputProps) {
  const generated = useId()
  const controlId = id ?? generated
  const invalid = Boolean(error)
  const [prefixRef, prefixWidth] = useAdornmentWidth(Boolean(prefix))
  const [suffixRef, suffixWidth] = useAdornmentWidth(Boolean(suffix))

  const control = (
    <div className="relative flex items-center">
      {prefix ? (
        <span
          ref={prefixRef}
          className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-sm text-ink-500"
        >
          {prefix}
        </span>
      ) : null}
      <input
        {...rest}
        id={controlId}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy(controlId, error, hint)}
        style={{
          ...style,
          ...(prefix
            ? {paddingInlineStart: `${PREFIX_INSET + prefixWidth + ADORNMENT_GAP}px`}
            : null),
          ...(suffix
            ? {paddingInlineEnd: `${SUFFIX_INSET + suffixWidth + ADORNMENT_GAP}px`}
            : null),
        }}
        className={cx(CONTROL_CLASSES, controlBorder(invalid), 'min-h-11', className)}
      />
      {suffix ? (
        <span
          ref={suffixRef}
          className="absolute inset-y-0 right-2 flex items-center text-sm text-ink-500"
        >
          {suffix}
        </span>
      ) : null}
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
