import {t} from '@/i18n'
import {Button, Modal, useFormFields} from '@/shell'

import {QuotaLimitField} from './QuotaLimitField'
import type {PlanLimit} from './orgTypes'
import {quotaConsequence, quotaFigure, quotaLabel} from './quotaVocabulary'

/**
 * `POST /admin/plans/{planId}/quotas` - one limit on one tier.
 *
 * One at a time, not a form of thirteen. `SetPlanQuota` upserts a single row, so a form
 * posting all thirteen would either send twelve unchanged values through the same
 * validation and audit path or need a diff computed in the browser - and the audit line an
 * operator comes looking for is "who raised the disk quota on Pro", not "who saved the
 * plan".
 *
 * The resource is fixed: the row that was tapped is the row being edited. `resource` still
 * goes in the body because `LimitForm` requires it, and hiding it in a disabled control
 * would suggest it could be changed.
 */
export function PlanLimitDialog({
  planId,
  limit,
  onClose,
}: {
  planId: string
  limit: PlanLimit
  onClose: () => void
}) {
  const form = useFormFields({
    resource: limit.resource,
    limitValue: String(limit.limit),
  })

  function save() {
    form.submit(`/admin/plans/${planId}/quotas`, {onSuccess: () => onClose()})
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={quotaLabel(limit.resource)}
      description={
        limit.explicit
          ? t('org.planLimit.descExplicit', {limit: quotaFigure(limit.resource, limit.limit)})
          : t('org.planLimit.descUnset')
      }
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            {t('org.planLimit.cancel')}
          </Button>
          <Button loading={form.processing} onClick={save}>
            {t('org.planLimit.save')}
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          save()
        }}
      >
        <QuotaLimitField
          resource={limit.resource}
          name="limitValue"
          value={form.data.limitValue}
          onValue={(raw) => form.set('limitValue', raw)}
          error={form.error('limitValue') ?? form.error('resource')}
        />

        <p className="text-sm leading-relaxed text-ink-600 dark:text-ink-400">
          {t('org.planLimit.atZero', {consequence: quotaConsequence(limit.resource).toLowerCase()})}
        </p>

        <p className="text-sm text-ink-500 dark:text-ink-400">
          {t('org.planLimit.scopeNote')}
        </p>

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}
