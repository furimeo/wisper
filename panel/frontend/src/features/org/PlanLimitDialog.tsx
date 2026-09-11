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
          ? `Currently ${quotaFigure(limit.resource, limit.limit)} on this plan.`
          : 'Never set on this plan, so it is zero: nothing of this kind is allowed at all.'
      }
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            Cancel
          </Button>
          <Button loading={form.processing} onClick={save}>
            Save limit
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
          At zero: {quotaConsequence(limit.resource).toLowerCase()} A tenant already over the new
          number keeps what it has and cannot add more.
        </p>

        <p className="text-sm text-ink-500 dark:text-ink-400">
          This applies to every organization on the tier, except the ones holding an exception
          for this resource.
        </p>

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}
