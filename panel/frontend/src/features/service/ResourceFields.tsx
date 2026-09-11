import {Input} from '@/shell'
import type {FormFields} from '@/shell'

import type {QuotaAllowance} from '@/features/org/orgTypes'
import {formatCores, quotaFigure, quotaLabel} from '@/features/org/quotaVocabulary'

import {ByteAmountField} from './ByteAmountField'
import type {ServiceFormValues} from './serviceFormValues'

/**
 * What the container is allowed to use.
 *
 * Every number here is a ceiling the node enforces through cgroups, and every one of them
 * counts against the organization's plan. So the plan's remaining headroom is printed next
 * to the two that are metered - a customer who types 8 GiB into a tenant with 2 GiB left
 * should find out here, not from a refusal after the eleventh field.
 *
 * CPU stays in millicores because that is the unit the column, the proto and the node all
 * use, and it is the only one where "500" is unambiguous. The equivalent in cores is
 * written underneath, which is how people actually think about it.
 */
export function ResourceFields({
  form,
  memoryAllowance,
  cpuAllowance,
  disabled,
}: {
  form: FormFields<ServiceFormValues>
  /** MEMORY_BYTES for this organization, when the screen was given it. */
  memoryAllowance?: QuotaAllowance
  /** CPU_MILLICORES for this organization. */
  cpuAllowance?: QuotaAllowance
  disabled?: boolean
}) {
  const millicores = Number.parseInt(form.data.cpuMillicores, 10)

  return (
    <div className="flex flex-col gap-4">
      <Input
        {...form.bind('cpuMillicores')}
        label="CPU"
        type="number"
        inputMode="numeric"
        min={1}
        max={64000}
        disabled={disabled}
        suffix="millicores"
        hint={[
          Number.isFinite(millicores) && millicores > 0 ? formatCores(millicores) : null,
          '1000 is one core.',
          remaining(cpuAllowance),
        ]
          .filter(Boolean)
          .join(' ')}
      />

      <ByteAmountField
        name="memoryBytes"
        label="Memory"
        bytes={form.data.memoryBytes}
        onBytes={(bytes) => form.set('memoryBytes', bytes)}
        error={form.error('memoryBytes')}
        disabled={disabled}
        hint={remaining(memoryAllowance) || 'Up to 64 GiB.'}
      />

      <ByteAmountField
        name="diskBytes"
        label="Disk"
        bytes={form.data.diskBytes}
        onBytes={(bytes) => form.set('diskBytes', bytes)}
        error={form.error('diskBytes')}
        disabled={disabled}
        hint="The container's own writable layer. Volumes are separate and are added later."
      />

      <Input
        {...form.bind('pidsLimit')}
        label="Process limit"
        type="number"
        inputMode="numeric"
        min={1}
        max={32768}
        disabled={disabled}
        hint="Enough for a runtime and its workers, few enough to stop a fork bomb taking the
          node with it."
      />

      <Input
        {...form.bind('requiredTags')}
        label="Placement tags"
        disabled={disabled}
        maxLength={500}
        autoComplete="off"
        placeholder="eu-west, ssd"
        hint="Optional. Only nodes carrying every tag are considered. Separate them with commas."
      />
    </div>
  )
}

/** "2 GB of 8 GB left on your plan." Empty when the screen was not given the allowance. */
function remaining(allowance: QuotaAllowance | undefined): string {
  if (!allowance) {
    return ''
  }
  const left = Math.max(0, allowance.limit - allowance.used)
  return `${quotaFigure(allowance.resource, left)} of ${quotaFigure(
    allowance.resource,
    allowance.limit,
  )} ${quotaLabel(allowance.resource).toLowerCase()} left on your plan.`
}
