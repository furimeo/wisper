import {t} from '@/i18n'
import {Input} from '@/shell'
import type {FormFields} from '@/shell'

import type {QuotaAllowance} from '@/features/org/orgTypes'
import {formatCores, quotaFigure, quotaLabel} from '@/features/org/quotaVocabulary'

import {ByteAmountField} from './ByteAmountField'
import type {ServiceFormValues} from './serviceFormValues'

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
        label={t('service.resources.cpu_label')}
        type="number"
        inputMode="numeric"
        min={1}
        max={64000}
        disabled={disabled}
        suffix={t('service.resources.millicores_suffix')}
        hint={[
          Number.isFinite(millicores) && millicores > 0 ? formatCores(millicores) : null,
          t('service.resources.one_core_hint'),
          remaining(cpuAllowance),
        ]
          .filter(Boolean)
          .join(' ')}
      />

      <ByteAmountField
        name="memoryBytes"
        label={t('service.resources.memory_label')}
        bytes={form.data.memoryBytes}
        onBytes={(bytes) => form.set('memoryBytes', bytes)}
        error={form.error('memoryBytes')}
        disabled={disabled}
        hint={remaining(memoryAllowance) || t('service.resources.memory_hint')}
      />

      <ByteAmountField
        name="diskBytes"
        label={t('service.resources.disk_label')}
        bytes={form.data.diskBytes}
        onBytes={(bytes) => form.set('diskBytes', bytes)}
        error={form.error('diskBytes')}
        disabled={disabled}
        hint={t('service.resources.disk_hint')}
      />

      <Input
        {...form.bind('pidsLimit')}
        label={t('service.resources.pids_label')}
        type="number"
        inputMode="numeric"
        min={1}
        max={32768}
        disabled={disabled}
        hint={t('service.resources.pids_hint')}
      />

      <Input
        {...form.bind('requiredTags')}
        label={t('service.resources.placement_tags_label')}
        disabled={disabled}
        maxLength={500}
        autoComplete="off"
        placeholder={t('service.resources.placement_tags_placeholder')}
        hint={t('service.resources.placement_tags_hint')}
      />
    </div>
  )
}

function remaining(allowance: QuotaAllowance | undefined): string {
  if (!allowance) {
    return ''
  }
  const left = Math.max(0, allowance.limit - allowance.used)
  return t('service.resources.remaining_allowance', {
    left: quotaFigure(allowance.resource, left),
    limit: quotaFigure(allowance.resource, allowance.limit),
    resource: quotaLabel(allowance.resource).toLowerCase(),
  })
}
