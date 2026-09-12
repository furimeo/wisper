import {useState} from 'react'

import {t} from '@/i18n'
import {Field, Input, Select, formatBytes} from '@/shell'

const UNITS = {
  MiB: 1024 * 1024,
  GiB: 1024 * 1024 * 1024,
} as const

type UnitName = keyof typeof UNITS

export function ByteAmountField({
  label,
  name,
  bytes,
  onBytes,
  hint,
  error,
  disabled,
  min,
}: {
  label: string
  /** The form field this writes, so the server's message lands under it. */
  name: string
  /** The current value in bytes, as the form holds it. Empty while unset. */
  bytes: string
  onBytes: (bytes: string) => void
  hint?: string
  error?: string
  disabled?: boolean
  /** The smallest number the customer may type, in the unit shown. Defaults to 1. */
  min?: number
}) {
  const parsed = Number.parseInt(bytes, 10)
  const known = Number.isFinite(parsed) && parsed > 0
  const [unit, setUnit] = useState<UnitName>(() =>
    known && parsed >= UNITS.GiB && parsed % UNITS.GiB === 0 ? 'GiB' : 'MiB',
  )

  const factor = UNITS[unit]
  const shown = known ? trim(parsed / factor) : ''

  return (
    <Field label={label} htmlFor={`${name}-amount`} error={error} hint={hintFor(hint, known, parsed)}>
      <div className="flex items-center gap-2">
        <Input
          id={`${name}-amount`}
          type="number"
          inputMode="decimal"
          min={min ?? 1}
          step="any"
          value={shown}
          disabled={disabled}
          className="flex-1"
          onChange={(event) => {
            const amount = Number.parseFloat(event.target.value)
            onBytes(Number.isFinite(amount) && amount > 0 ? String(Math.round(amount * factor)) : '')
          }}
        />
        <Select
          aria-label={t('service.byte_amount.unit_label', {label})}
          value={unit}
          disabled={disabled}
          className="w-28"
          onChange={(event) => setUnit(event.target.value as UnitName)}
          options={[
            {value: 'MiB', label: 'MiB'},
            {value: 'GiB', label: 'GiB'},
          ]}
        />
      </div>
    </Field>
  )
}

function hintFor(hint: string | undefined, known: boolean, bytes: number): string | undefined {
  if (!known) {
    return hint
  }
  const exact = formatBytes(bytes)
  return hint ? `${hint} ${t('service.byte_amount.currently', {exact})}` : exact
}

/** 1.5 stays 1.5; 5.0 becomes 5. Four decimals is past anything a person types. */
function trim(value: number): string {
  return String(Math.round(value * 10_000) / 10_000)
}
