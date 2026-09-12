import {useState} from 'react'

import {t} from '@/i18n'
import {Field, Input, Select} from '@/shell'

import type {QuotaResource} from './orgTypes'
import {quotaFigure, quotaLabel, quotaUnit} from './quotaVocabulary'

/**
 * A quota limit, typed in the unit a person thinks in and submitted as the raw number the
 * column stores.
 *
 * `quota.limit_value` and `quota_override.limit_value` are a single `bigint` for thirteen
 * different things: a count of projects, a number of bytes, a number of millicores. An
 * operator asked to type 274877906944 for 256 GiB gets it wrong, and the way they get it
 * wrong is by a factor of 1024 - which on a disk quota is the difference between a plan
 * and a mistake somebody notices a month later.
 *
 * So the control changes shape with the resource: a plain integer for a count, a number
 * and a unit for a size, cores for CPU. What leaves is always the raw figure, and the
 * exact value is echoed underneath so there is nothing to take on trust.
 */
const BYTE_UNITS = {
  MiB: 1024 ** 2,
  GiB: 1024 ** 3,
  TiB: 1024 ** 4,
} as const

type ByteUnit = keyof typeof BYTE_UNITS

export function QuotaLimitField({
  resource,
  name,
  value,
  onValue,
  error,
  disabled,
}: {
  resource: QuotaResource
  /** The form field this writes, so the server's message lands under it. */
  name: string
  /** The raw limit as the form holds it - a decimal string, or empty. */
  value: string
  onValue: (raw: string) => void
  error?: string
  disabled?: boolean
}) {
  const unit = quotaUnit(resource)
  const raw = Number.parseInt(value, 10)
  const known = Number.isFinite(raw) && raw >= 0

  const [byteUnit, setByteUnit] = useState<ByteUnit>(() => startingUnit(known ? raw : 0))

  if (unit === 'count') {
    return (
      <Input
        label={quotaLabel(resource)}
        name={name}
        type="number"
        inputMode="numeric"
        min={0}
        step={1}
        value={known ? String(raw) : ''}
        disabled={disabled}
        error={error}
        onChange={(event) => onValue(digits(event.target.value))}
        hint={t('org.quota.zeroCountHint')}
      />
    )
  }

  if (unit === 'millicores') {
    const cores = known ? raw / 1000 : NaN
    return (
      <Input
        label={quotaLabel(resource)}
        name={name}
        type="number"
        inputMode="decimal"
        min={0}
        step="0.25"
        value={Number.isFinite(cores) ? trim(cores) : ''}
        disabled={disabled}
        error={error}
        suffix="cores"
        onChange={(event) => {
          const typed = Number.parseFloat(event.target.value)
          onValue(Number.isFinite(typed) && typed >= 0 ? String(Math.round(typed * 1000)) : '')
        }}
        hint={known ? t('org.quota.storedMillicores', {raw}) : t('org.quota.coresHint')}
      />
    )
  }

  const factor = BYTE_UNITS[byteUnit]
  const shown = known ? trim(raw / factor) : ''

  return (
    <Field
      label={quotaLabel(resource)}
      htmlFor={`${name}-amount`}
      error={error}
      hint={
        known
          ? t('org.quota.storedBytes', {raw, formatted: quotaFigure(resource, raw)})
          : t('org.quota.zeroBytesHint')
      }
    >
      <div className="flex items-center gap-2">
        <Input
          id={`${name}-amount`}
          type="number"
          inputMode="decimal"
          min={0}
          step="any"
          value={shown}
          disabled={disabled}
          className="flex-1"
          onChange={(event) => {
            const typed = Number.parseFloat(event.target.value)
            onValue(Number.isFinite(typed) && typed >= 0 ? String(Math.round(typed * factor)) : '')
          }}
        />
        <Select
          aria-label={`${quotaLabel(resource)} unit`}
          value={byteUnit}
          disabled={disabled}
          className="w-28"
          onChange={(event) => setByteUnit(event.target.value as ByteUnit)}
          options={[
            {value: 'MiB', label: 'MiB'},
            {value: 'GiB', label: 'GiB'},
            {value: 'TiB', label: 'TiB'},
          ]}
        />
      </div>
    </Field>
  )
}

/**
 * The unit the existing figure divides into cleanly.
 *
 * Chosen once and then left to the operator, because recomputing it on every keystroke
 * makes "1024" typed into a MiB box jump to "1 GiB" mid-word.
 */
function startingUnit(bytes: number): ByteUnit {
  if (bytes > 0 && bytes % BYTE_UNITS.TiB === 0) {
    return 'TiB'
  }
  if (bytes > 0 && bytes % BYTE_UNITS.GiB === 0) {
    return 'GiB'
  }
  return bytes >= BYTE_UNITS.GiB ? 'GiB' : 'MiB'
}

/** Digits only: a limit is a whole number, and `type=number` still accepts `1e3`. */
function digits(typed: string): string {
  const cleaned = typed.replace(/[^\d]/g, '')
  return cleaned === '' ? '' : String(Number.parseInt(cleaned, 10))
}

/** 1.5 stays 1.5; 5.0 becomes 5. */
function trim(value: number): string {
  return String(Math.round(value * 10_000) / 10_000)
}
