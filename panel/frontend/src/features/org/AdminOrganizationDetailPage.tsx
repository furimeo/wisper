import {Head, usePage} from '@inertiajs/react'

import {Badge, Card, CardFact, CardFacts, PageHeader, RelativeTime} from '@/shell'

import {QuotaAlert} from './QuotaAlert'
import {QuotaMeter} from './QuotaMeter'
import {QuotaOverrideList} from './QuotaOverrideList'
import {TenantPlanCard} from './TenantPlanCard'
import {TenantStatusCard} from './TenantStatusCard'
import type {
  MemberView,
  Organization,
  Plan,
  QuotaAllowance,
  QuotaOverride,
  QuotaResource,
} from './orgTypes'
import {roleLabel} from './roleVocabulary'

/**
 * `GET /admin/organizations/{organizationId}` - one tenant, in full.
 *
 * The order is the order an operator opened it in: what is wrong (the alert), what it is
 * on (the plan), what it is allowed (the limits and the exceptions), who to contact (the
 * people), and last the button that stops them creating anything.
 *
 * `allowances` already has the exceptions folded in - `QuotaGuard` resolves override, then
 * plan, then nothing - so the limits list is what is actually in force and the exceptions
 * list below it explains why one of the numbers is not the plan's. Two lists rather than
 * one annotated list, because they are edited in different places.
 *
 * The member list here is read-only and deliberately so. An operator is not a member of
 * the tenants they administer, and there is no use-case letting them change somebody's
 * role from outside - the owners do that from `/orgs/{id}/members`. What this shows is who
 * to email.
 */
type AdminOrganizationDetailProps = {
  tenant: Organization
  /** The tier it is on, or null when the plan row has gone. */
  plan: Plan | null
  /** Every plan, selectable first. */
  plans: Plan[]
  /** What is in force, exceptions already applied. */
  allowances: QuotaAllowance[]
  overrides: QuotaOverride[]
  members: MemberView[]
  /** `QuotaResource.values()`, for the grant form. */
  resources: QuotaResource[]
}

export default function AdminOrganizationDetailPage() {
  const {tenant, plan, plans, allowances, overrides, members, resources} =
    usePage<AdminOrganizationDetailProps>().props

  const accepted = members.filter((member) => member.accepted)
  const owners = accepted.filter((member) => member.role === 'OWNER')

  return (
    <div className="flex flex-col gap-4">
      <Head title={tenant.name} />

      <PageHeader
        title={tenant.name}
        description={`/${tenant.slug} · ${plan ? plan.name : 'no plan'} · ${accepted.length} ${accepted.length === 1 ? 'member' : 'members'}`}
      />

      {tenant.suspended ? (
        <div className="rounded-xl border border-failed/40 bg-failed/10 px-4 py-3.5 md:px-5">
          <p className="text-sm font-semibold text-ink-900 dark:text-ink-100">Suspended</p>
          <p className="mt-1 text-sm leading-relaxed text-ink-800 dark:text-ink-200">
            {tenant.suspensionReason || 'No reason was recorded on the record.'}
          </p>
        </div>
      ) : null}

      <QuotaAlert allowances={allowances} />

      <Card title="Record">
        <CardFacts>
          <CardFact label="Address">
            <code className="font-mono">/{tenant.slug}</code>
          </CardFact>
          <CardFact label="Plan">
            {plan ? (
              <>
                {plan.name} <Badge tone="neutral">{plan.code}</Badge>
              </>
            ) : (
              'None - every limit is zero'
            )}
          </CardFact>
          <CardFact label="Opened">
            <RelativeTime at={tenant.createdAt} />
          </CardFact>
          <CardFact label="Last change">
            <RelativeTime at={tenant.updatedAt} />
          </CardFact>
        </CardFacts>
      </Card>

      <TenantPlanCard tenant={tenant} plan={plan} plans={plans} />

      <Card
        title="Limits in force"
        description="The plan's numbers, with any exception below already applied."
        padded={false}
      >
        <ul className="divide-y divide-ink-200 px-4 md:px-5 dark:divide-ink-800">
          {allowances.map((allowance) => (
            <li key={allowance.resource}>
              <QuotaMeter allowance={allowance} />
            </li>
          ))}
        </ul>
      </Card>

      <QuotaOverrideList
        organizationId={tenant.id}
        overrides={overrides}
        resources={resources}
        allowances={allowances}
      />

      <Card
        title="People"
        description={
          owners.length === 0
            ? 'Nobody here owns this organization, which should not happen - it is worth looking at.'
            : `${accepted.length} with access, ${owners.length} ${owners.length === 1 ? 'owner' : 'owners'}. Members are managed by the owners, not from here.`
        }
        padded={false}
      >
        {members.length === 0 ? (
          <p className="px-4 py-4 text-sm text-ink-600 md:px-5 dark:text-ink-400">
            No member rows at all. The tenant exists and nobody can reach it.
          </p>
        ) : (
          <ul className="divide-y divide-ink-200 dark:divide-ink-800">
            {members.map((member) => (
              <li key={member.id} className="px-4 py-3 md:px-5">
                <p className="flex flex-wrap items-center gap-2">
                  <span className="truncate text-sm font-medium text-ink-900 dark:text-ink-100">
                    {member.email}
                  </span>
                  <Badge tone={member.accepted ? 'neutral' : 'degraded'}>
                    {member.accepted ? roleLabel(member.role) : 'Invited'}
                  </Badge>
                </p>
                <p className="mt-0.5 truncate text-xs text-ink-500 dark:text-ink-400">
                  {member.displayName}
                  {member.acceptedAt ? (
                    <>
                      {' · joined '}
                      <RelativeTime at={member.acceptedAt} />
                    </>
                  ) : member.invitedAt ? (
                    <>
                      {' · invited '}
                      <RelativeTime at={member.invitedAt} />
                    </>
                  ) : null}
                </p>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <TenantStatusCard tenant={tenant} />
    </div>
  )
}
