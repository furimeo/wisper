import {Button, Input, Modal, Select, Textarea, useFormFields} from '@/shell'

import {QuotaLimitField} from './QuotaLimitField'
import type {QuotaAllowance, QuotaResource} from './orgTypes'
import {quotaFigure, quotaLabel} from './quotaVocabulary'

/**
 * `POST /admin/organizations/{id}/quota-overrides` - one tenant's exception to its plan.
 *
 * An exception may be lower than the plan as well as higher, and the form says so. The
 * obvious use is a customer who needs one more database for a fortnight; the less obvious
 * one is a tenant that has to be capped without being moved off the tier it is paying for,
 * and an operator who believes this only raises limits will move them instead.
 *
 * Expiry is in days rather than a date. Somebody raising a limit "for the migration next
 * week" is thinking in days, and a date picker on a phone, in a timezone that may not be
 * the server's, is three chances at an off-by-one. Left empty it stands until it is
 * revoked.
 *
 * The current allowance is shown next to the input, because the number worth typing is
 * relative to the one already in force.
 */
export function GrantOverrideDialog({
  open,
  onClose,
  organizationId,
  resources,
  allowances,
}: {
  open: boolean
  onClose: () => void
  organizationId: string
  /** `QuotaResource.values()`. */
  resources: QuotaResource[]
  /** What is in force now, so the form can say what is being replaced. */
  allowances: QuotaAllowance[]
}) {
  const form = useFormFields({
    resource: resources[0] ?? 'PROJECT',
    limitValue: '',
    reason: '',
    expiresInDays: '',
  })

  const resource = form.data.resource as QuotaResource
  const current = allowances.find((allowance) => allowance.resource === resource)

  function grant() {
    form.submit(`/admin/organizations/${organizationId}/quota-overrides`, {
      onSuccess: () => {
        form.reset()
        onClose()
      },
    })
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Grant an exception"
      description="Replaces one limit for this organization only. The plan is left alone."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            Cancel
          </Button>
          <Button loading={form.processing} onClick={grant}>
            Grant exception
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          grant()
        }}
      >
        <Select
          {...form.bind('resource')}
          label="Limit"
          required
          options={resources.map((value) => ({value, label: quotaLabel(value)}))}
          hint={
            current
              ? `In force now: ${quotaFigure(resource, current.limit)}, with ${quotaFigure(resource, current.used)} used.`
              : 'Nothing is in force for this resource yet.'
          }
        />

        <QuotaLimitField
          key={resource}
          resource={resource}
          name="limitValue"
          value={form.data.limitValue}
          onValue={(raw) => form.set('limitValue', raw)}
          error={form.error('limitValue')}
        />

        <Textarea
          {...form.bind('reason')}
          label="Reason"
          required
          maxLength={500}
          hint="Kept on the exception and in the audit trail. Whoever finds this in six months is you."
        />

        <Input
          {...form.bind('expiresInDays')}
          label="Expires after"
          type="number"
          inputMode="numeric"
          min={1}
          max={3650}
          step={1}
          suffix="days"
          hint="Leave it empty for an exception that stands until it is revoked."
        />

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}
