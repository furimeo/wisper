import {t} from '@/i18n'
import {Select, Textarea} from '@/shell'
import type {FormFields} from '@/shell'

import type {ServiceFormValues} from './serviceFormValues'
import type {RuntimeIsolation} from './serviceTypes'
import {isolationHint, isolationLabel, isolationNeedsReason, runcWarning} from './serviceVocabulary'

export function IsolationFields({
  form,
  isolations,
  disabled,
}: {
  form: FormFields<ServiceFormValues>
  isolations: RuntimeIsolation[]
  disabled?: boolean
}) {
  const chosen = form.data.runtimeIsolation as RuntimeIsolation
  const needsReason = isolationNeedsReason(chosen)

  return (
    <div className="flex flex-col gap-4">
      <Select
        {...form.bind('runtimeIsolation')}
        label={t('service.isolation.label')}
        disabled={disabled}
        hint={isolationHint(chosen)}
        options={isolations.map((value) => ({value, label: isolationLabel(value)}))}
      />

      {needsReason ? (
        <>
          <p className="rounded-lg border border-degraded/40 bg-degraded/10 px-3 py-2 text-sm text-ink-800 dark:text-ink-100">
            {runcWarning()}
          </p>
          <Textarea
            {...form.bind('isolationReason')}
            label={t('service.isolation.reason_label')}
            required
            disabled={disabled}
            maxLength={500}
            hint={t('service.isolation.reason_hint')}
          />
        </>
      ) : null}
    </div>
  )
}
