import {Head, Link, router, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {Badge, Button, Card, EmptyState, Icon, PageHeader, askConfirmation} from '@/shell'

import {CreatePlanDialog} from './CreatePlanDialog'
import type {Plan} from './orgTypes'

/**
 * `GET /admin/plans` - the tiers this installation offers.
 *
 * Without this screen the `plan` and `quota` tables are reachable only by hand-written SQL,
 * and a fresh installation cannot open its first organization at all: `CreateOrganization`
 * refuses when there is no default plan, on purpose, because a tenant on no plan has every
 * limit at zero and looks like a broken panel.
 *
 * So the no-default case is the loudest thing on the page. It is the one state where the
 * platform is technically running and functionally unusable, and the fix is one tap.
 *
 * Retiring a plan is not deleting it. Tenants already on it stay on it and keep their
 * limits; it simply stops being offered to new ones. The wording says that, because
 * "archive" next to a list of paying customers is a word an operator will hesitate over
 * for the wrong reason.
 */
type AdminPlanListProps = {
  /** Selectable first, as `findAllSelectableFirst()` returned them. */
  plans: Plan[]
}

export default function AdminPlanListPage() {
  const {plans} = usePage<AdminPlanListProps>().props
  const [creating, setCreating] = useState(false)
  const [working, setWorking] = useState<string | null>(null)

  const hasDefault = plans.some((plan) => plan.isDefault && plan.selectable)

  function post(path: string, planId: string) {
    setWorking(planId)
    router.post(path, {}, {preserveScroll: true, onFinish: () => setWorking(null)})
  }

  async function archive(plan: Plan) {
    const confirmed = await askConfirmation({
      title: `Retire ${plan.code}?`,
      body:
        'It stops being offered to new organizations. Everyone already on it stays on it with ' +
        'exactly the limits they have now, and you can bring it back at any time.',
      confirmLabel: 'Retire it',
      tone: 'danger',
    })
    if (confirmed) {
      post(`/admin/plans/${plan.id}/archive`, plan.id)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Head title="Plans" />

      <PageHeader
        title="Plans"
        description="What an organization is allowed. Every tenant is on exactly one, and exceptions are granted per tenant."
        actions={
          <Button icon={<Icon name="plan" />} onClick={() => setCreating(true)}>
            New plan
          </Button>
        }
      />

      {!hasDefault && plans.length > 0 ? (
        <div className="rounded-xl border border-failed/40 bg-failed/10 px-4 py-3.5 md:px-5">
          <p className="text-sm font-semibold text-ink-900 dark:text-ink-100">
            No selectable default plan
          </p>
          <p className="mt-1 text-sm leading-relaxed text-ink-800 dark:text-ink-200">
            Opening an organization is refused while this is true, because a tenant on no plan
            has every limit at zero. Make one of the tiers below the default.
          </p>
        </div>
      ) : null}

      {plans.length === 0 ? (
        <Card padded={false}>
          <EmptyState
            icon={<Icon name="plan" />}
            title="No plans yet"
            description="Nothing can be created on this installation until there is one: an organization
              needs a plan, and a plan is what says how many projects, services and gigabytes it may
              have. Make one, set its limits, and mark it as the default."
            action={<Button onClick={() => setCreating(true)}>Create the first plan</Button>}
          />
        </Card>
      ) : (
        <ul className="flex flex-col gap-2">
          {plans.map((plan) => (
            <li
              key={plan.id}
              className="rounded-xl border border-ink-200 bg-white px-4 py-3.5 dark:border-ink-800 dark:bg-ink-900"
            >
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0">
                  <Link
                    href={`/admin/plans/${plan.id}`}
                    className="flex flex-wrap items-center gap-2"
                  >
                    <span className="truncate text-sm font-semibold text-ink-900 dark:text-ink-100">
                      {plan.name}
                    </span>
                    <code className="font-mono text-xs text-ink-500 dark:text-ink-400">
                      {plan.code}
                    </code>
                    {plan.isDefault ? <Badge tone="accent">Default</Badge> : null}
                    {plan.selectable ? null : <Badge tone="neutral">Retired</Badge>}
                  </Link>

                  {plan.description ? (
                    <p className="mt-1 text-sm leading-relaxed text-ink-600 dark:text-ink-400">
                      {plan.description}
                    </p>
                  ) : null}
                </div>

                <div className="flex flex-col gap-2 sm:shrink-0 sm:flex-row">
                  {plan.selectable && !plan.isDefault ? (
                    <Button
                      variant="secondary"
                      size="sm"
                      loading={working === plan.id}
                      onClick={() => post(`/admin/plans/${plan.id}/default`, plan.id)}
                    >
                      Make default
                    </Button>
                  ) : null}

                  {plan.selectable ? (
                    <Button
                      variant="secondary"
                      size="sm"
                      loading={working === plan.id}
                      onClick={() => void archive(plan)}
                    >
                      Retire
                    </Button>
                  ) : (
                    <Button
                      variant="secondary"
                      size="sm"
                      loading={working === plan.id}
                      onClick={() => post(`/admin/plans/${plan.id}/restore`, plan.id)}
                    >
                      Offer again
                    </Button>
                  )}
                </div>
              </div>

              <Link
                href={`/admin/plans/${plan.id}`}
                className="mt-2 inline-flex touch-target items-center gap-1 text-sm font-medium text-accent-600 dark:text-accent-400"
              >
                Limits and tenants
                <Icon name="chevronRight" className="size-4" />
              </Link>
            </li>
          ))}
        </ul>
      )}

      <CreatePlanDialog open={creating} onClose={() => setCreating(false)} />
    </div>
  )
}
