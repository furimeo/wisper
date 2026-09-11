import type {ReactNode, TextareaHTMLAttributes} from 'react'
import {useEffect, useId, useRef} from 'react'

import {cx} from './cx'
import {Field, describedBy} from './Field'
import {CONTROL_CLASSES, controlBorder} from './Input'

/**
 * A multi-line text control that grows with its content.
 *
 * A fixed-height box on a phone shows three lines of a twenty-line environment value and
 * hides the rest behind an inner scrollbar the customer cannot see the edges of. Growing
 * to fit, up to a cap, keeps the whole value in the page's own scroll - which is the
 * scroll a thumb already knows how to use.
 *
 * This is not the code editor. Editing a file is CodeMirror, in `features/files`.
 */
export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: ReactNode
  hint?: ReactNode
  error?: string
  /** Grow to fit the content, up to `maxRows`. On by default. */
  autoGrow?: boolean
  maxRows?: number
  fieldClassName?: string
}

export function Textarea({
  label,
  hint,
  error,
  autoGrow = true,
  maxRows = 12,
  fieldClassName,
  className,
  id,
  rows = 3,
  value,
  ...rest
}: TextareaProps) {
  const generated = useId()
  const controlId = id ?? generated
  const invalid = Boolean(error)
  const element = useRef<HTMLTextAreaElement | null>(null)

  useEffect(() => {
    const field = element.current
    if (!autoGrow || !field) {
      return
    }
    // Collapse first: without it the box can only ever grow, never shrink again after a
    // paste is deleted.
    field.style.height = 'auto'
    const lineHeight = Number.parseFloat(getComputedStyle(field).lineHeight) || 20
    const cap = lineHeight * maxRows
    field.style.height = `${Math.min(field.scrollHeight, cap)}px`
    field.style.overflowY = field.scrollHeight > cap ? 'auto' : 'hidden'
  }, [autoGrow, maxRows, value])

  const control = (
    <textarea
      {...rest}
      value={value}
      ref={element}
      id={controlId}
      rows={rows}
      aria-invalid={invalid || undefined}
      aria-describedby={describedBy(controlId, error, hint)}
      className={cx(CONTROL_CLASSES, controlBorder(invalid), 'py-2.5 leading-6', className)}
    />
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
