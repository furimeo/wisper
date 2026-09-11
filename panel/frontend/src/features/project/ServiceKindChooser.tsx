import {cx} from '@/shell'

import type {ServiceKind} from '@/features/service/serviceTypes'
import {kindDescription, kindLabel} from '@/features/service/serviceVocabulary'

/**
 * The first and only irreversible choice on the new-service form.
 *
 * `service.kind` cannot be changed afterwards - there is no field for it in `SettingsForm`
 * - because an app and a site are different rows in three CHECK constraints and different
 * things on a node. So it is asked first, in full sentences, as two large targets rather
 * than a dropdown: a mis-tap here costs the customer the whole form.
 */
export function ServiceKindChooser({
  kinds,
  value,
  onChange,
  disabled,
}: {
  kinds: ServiceKind[]
  value: ServiceKind | ''
  onChange: (kind: ServiceKind) => void
  disabled?: boolean
}) {
  return (
    <fieldset className="flex flex-col gap-2" disabled={disabled}>
      <legend className="mb-1 text-sm font-medium text-ink-700 dark:text-ink-300">
        What are you deploying?
      </legend>

      {kinds.map((kind) => {
        const selected = value === kind
        return (
          <label
            key={kind}
            className={cx(
              'flex cursor-pointer items-start gap-3 rounded-xl border px-4 py-3.5 transition-colors',
              selected
                ? 'border-accent-500 bg-accent-500/5'
                : 'border-ink-200 bg-white hover:border-ink-300 dark:border-ink-800 dark:bg-ink-900',
            )}
          >
            <input
              type="radio"
              name="kind"
              value={kind}
              checked={selected}
              onChange={() => onChange(kind)}
              className="mt-0.5 size-5 shrink-0 accent-accent-600"
            />
            <span className="min-w-0">
              <span className="block text-sm font-semibold text-ink-900 dark:text-ink-100">
                {kindLabel(kind)}
              </span>
              <span className="mt-0.5 block text-sm leading-relaxed text-ink-600 dark:text-ink-400">
                {kindDescription(kind)}
              </span>
            </span>
          </label>
        )
      })}
    </fieldset>
  )
}
