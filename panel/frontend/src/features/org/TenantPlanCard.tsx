import {t} from '@/i18n'
import {Button, Card, Select, useFormFields} from '@/shell'

import type {Organization, Plan} from './orgTypes'

/**
 * `POST /admin/organizations/{id}/plan` - moving a tenant between tiers.
 *
 * A retired plan is offered when the tenant is already on it and not otherwise. `AssignPlan`
 * refuses a move onto an archived tier, but a picker that silently drops the tenant's own
 * plan would show an operator a control whose current value is missing - which reads as
 * "no plan" for an organization that has one.
 *
 * Moving a tenant changes the ceiling, never the usage. An organization that ends up over
 * a limit keeps everything it has and cannot create more, which is what the sentence under
 * the picker says: the alternative interpretation is that lowering a plan deletes
 * something, and an operator who believes that will not do it when they should.
 */
export function TenantPlanCard({
  tenant,
  plan,
  plans,
}: {
  tenant: Organization
  /** The tier it is on now, or null when the row is gone. */
  plan: Plan | null
  /** Every plan, selectable first. */
  plans: Plan[]
}) {
  const form = useFormFields({planId: tenant.planId})

  const options = plans
    .filter((candidate) => candidate.selectable || candidate.id === tenant.planId)
    .map((candidate) => ({
      value: candidate.id,
      label: label(candidate, candidate.id === tenant.planId),
    }))

  return (
    <Card
      title={t('org.tenantPlan.title')}
      description={
        plan
          ? t('org.tenantPlan.desc', {name: tenant.name, plan: plan.name})
          : t('org.tenantPlan.descNoPlan')
      }
      footer={
        <Button
          block
          className="sm:w-auto"
          loading={form.processing}
          disabled={form.data.planId === tenant.planId}
          onClick={() => form.submit(`/admin/organizations/${tenant.id}/plan`)}
        >
          {t('org.tenantPlan.btn')}
        </Button>
      }
    >
      <form
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault()
          form.submit(`/admin/organizations/${tenant.id}/plan`)
        }}
      >
        <Select
          {...form.bind('planId')}
          label={t('org.tenantPlan.tier')}
          required
          options={options}
          hint={t('org.tenantPlan.hint')}
        />
        <p className="text-sm leading-relaxed text-ink-600 dark:text-ink-400">
          {t('org.tenantPlan.note')}
        </p>
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Card>
  )
}

function label(plan: Plan, current: boolean): string {
  const marks = [
    plan.isDefault ? t('org.tenantPlan.tagDefault') : null,
    plan.selectable ? null : t('org.tenantPlan.tagRetired'),
    current ? t('org.tenantPlan.tagCurrent') : null,
  ]
    .filter((mark): mark is string => mark !== null)
    .join(', ')
  return marks ? `${plan.name} (${marks})` : plan.name
}
