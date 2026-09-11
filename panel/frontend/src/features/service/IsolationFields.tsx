import {Select, Textarea} from '@/shell'
import type {FormFields} from '@/shell'

import type {ServiceFormValues} from './serviceFormValues'
import type {RuntimeIsolation} from './serviceTypes'
import {RUNC_WARNING, isolationHint, isolationLabel, isolationNeedsReason} from './serviceVocabulary'

/**
 * gVisor, or the documented way out of it.
 *
 * The escape hatch exists because `runsc` does not run everything - io_uring and a handful
 * of older binaries fail under it - and a platform with no way out turns "gVisor cannot do
 * this" into "your app is broken". It is deliberately awkward to take: the reason is
 * required, the CHECK constraint refuses a blank one, and the panel prints it next to the
 * service afterwards. A weaker sandbox nobody can see is the one that gets chosen by
 * default six months later.
 */
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
        label="Isolation"
        disabled={disabled}
        hint={isolationHint(chosen)}
        options={isolations.map((value) => ({value, label: isolationLabel(value)}))}
      />

      {needsReason ? (
        <>
          <p className="rounded-lg border border-degraded/40 bg-degraded/10 px-3 py-2 text-sm text-ink-800 dark:text-ink-100">
            {RUNC_WARNING}
          </p>
          <Textarea
            {...form.bind('isolationReason')}
            label="Why gVisor is off"
            required
            disabled={disabled}
            maxLength={500}
            hint="Say which workload cannot run under runsc. This is shown next to the service."
          />
        </>
      ) : null}
    </div>
  )
}
