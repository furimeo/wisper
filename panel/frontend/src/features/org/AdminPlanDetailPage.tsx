import {Head, router, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
import {
  Badge,
  Button,
  Card,
  CardFact,
  CardFacts,
  Icon,
  PageHeader,
  RelativeTime,
  askConfirmation,
} from '@/shell'

import {PlanLimitDialog} from './PlanLimitDialog'
import type {Plan, PlanLimit} from './orgTypes'
import {quotaConsequence, quotaFigure, quotaLabel} from './quotaVocabulary'

/**
 * `GET /admin/plans/{planId}` - one tier and every limit on it.
 *
 * All thirteen resources are listed, including the ones with no `quota` row. A screen that
 * showed only the rows that exist could not be used to add the missing ones, and the
 * missing ones are exactly what an operator is looking for - a plan that allows six
 * services and no memory is a plan whose first service is refused.
 *
 * "Set to zero" and "never set" are drawn differently for the same reason. Both refuse
 * everything, and only one of them was a decision.
 */
type AdminPlanDetailProps = {
  plan: Plan
  /** Every `QuotaResource`, zeros included. */
  limits: PlanLimit[]
  /** How many organizations this would affect. */
  tenantCount: number
}

export default function AdminPlanDetailPage() {
  const {plan, limits, tenantCount} = usePage<AdminPlanDetailProps>().props
  const [editing, setEditing] = useState<PlanLimit | null>(null)
  const [working, setWorking] = useState(false)

  const unset = limits.filter((limit) => !limit.explicit).length

  function post(path: string) {
    setWorking(true)
    router.post(path, {}, {preserveScroll: true, onFinish: () => setWorking(false)})
  }

  async function archive() {
    const confirmed = await askConfirmation({
      title: t('org.planDetail.confirmRetireTitle', {code: plan.code}),
      body: t('org.planDetail.confirmRetireBody', {count: tenantCount}),
      confirmLabel: t('org.planDetail.confirmRetireBtn'),
      tone: 'danger',
    })
    if (confirmed) {
      post(`/admin/plans/${plan.id}/archive`)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Head title={plan.name} />

      <PageHeader
        title={plan.name}
        description={plan.description || t('org.planDetail.fallbackDesc', {code: plan.code})}
        actions={
          plan.selectable && !plan.isDefault ? (
            <Button
              variant="secondary"
              loading={working}
              onClick={() => post(`/admin/plans/${plan.id}/default`)}
            >
              {t('org.plans.makeDefault')}
            </Button>
          ) : null
        }
      />

      {unset > 0 ? (
        <div className="rounded-xl border border-degraded/40 bg-degraded/10 px-4 py-3.5 md:px-5">
          <p className="text-sm font-semibold text-ink-900 dark:text-ink-100">
            {t('org.planDetail.unsetAlertTitle', {count: unset})}
          </p>
          <p className="mt-1 text-sm leading-relaxed text-ink-800 dark:text-ink-200">
            {t('org.planDetail.unsetAlertDesc')}
          </p>
        </div>
      ) : null}

      <Card
        title={t('org.planDetail.tierCardTitle')}
        action={
          <div className="flex items-center gap-2">
            {plan.isDefault ? <Badge tone="accent">{t('org.plans.badgeDefault')}</Badge> : null}
            {plan.selectable ? null : <Badge tone="neutral">{t('org.plans.badgeRetired')}</Badge>}
          </div>
        }
        footer={
          plan.selectable ? (
            <Button variant="secondary" block className="sm:w-auto" loading={working} onClick={() => void archive()}>
              {t('org.planDetail.retireBtn')}
            </Button>
          ) : (
            <Button
              variant="secondary"
              block
              className="sm:w-auto"
              loading={working}
              onClick={() => post(`/admin/plans/${plan.id}/restore`)}
            >
              {t('org.planDetail.offerAgainBtn')}
            </Button>
          )
        }
      >
        <CardFacts>
          <CardFact label={t('org.planDetail.code')}>
            <code className="font-mono">{plan.code}</code>
          </CardFact>
          <CardFact label={t('org.planDetail.orgsOnIt')}>{tenantCount}</CardFact>
          <CardFact label={t('org.planDetail.created')}>
            <RelativeTime at={plan.createdAt} />
          </CardFact>
          <CardFact label={t('org.planDetail.lastChange')}>
            <RelativeTime at={plan.updatedAt} />
          </CardFact>
        </CardFacts>
        {plan.selectable ? null : (
          <p className="mt-2 text-sm leading-relaxed text-ink-600 dark:text-ink-400">
            {t('org.planDetail.retiredNotice', {count: tenantCount})}
          </p>
        )}
      </Card>

      <Card
        title={t('org.planDetail.limitsTitle')}
        description={t('org.planDetail.limitsDesc', {count: tenantCount})}
        padded={false}
      >
        <ul className="divide-y divide-ink-200 dark:divide-ink-800">
          {limits.map((limit) => (
            <li key={limit.resource}>
              <button
                type="button"
                onClick={() => setEditing(limit)}
                className="flex w-full touch-target items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-ink-100 md:px-5 dark:hover:bg-ink-800"
              >
                <span className="min-w-0 flex-1">
                  <span className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-medium text-ink-900 dark:text-ink-100">
                      {quotaLabel(limit.resource)}
                    </span>
                    {limit.explicit ? null : <Badge tone="degraded">{t('org.planDetail.neverSetBadge')}</Badge>}
                  </span>
                  <span className="mt-0.5 block text-xs text-ink-500 dark:text-ink-400">
                    {limit.limit === 0
                      ? quotaConsequence(limit.resource)
                      : t('org.planDetail.upTo', {amount: quotaFigure(limit.resource, limit.limit)})}
                  </span>
                </span>

                <span className="shrink-0 text-sm font-medium tabular-nums text-ink-900 dark:text-ink-100">
                  {quotaFigure(limit.resource, limit.limit)}
                </span>
                <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" />
              </button>
            </li>
          ))}
        </ul>
      </Card>

      {editing ? (
        <PlanLimitDialog
          key={editing.resource}
          planId={plan.id}
          limit={editing}
          onClose={() => setEditing(null)}
        />
      ) : null}
    </div>
  )
}
