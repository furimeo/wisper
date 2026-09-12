import {Head, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
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
        description={t('org.adminDetail.desc', {
          slug: tenant.slug,
          plan: plan ? plan.name : t('org.adminDetail.noPlan'),
          members: t('org.adminDetail.memberCount', {count: accepted.length}),
        })}
      />

      {tenant.suspended ? (
        <div className="rounded-xl border border-failed/40 bg-failed/10 px-4 py-3.5 md:px-5">
          <p className="text-sm font-semibold text-ink-900 dark:text-ink-100">{t('org.adminDetail.suspendedTitle')}</p>
          <p className="mt-1 text-sm leading-relaxed text-ink-800 dark:text-ink-200">
            {tenant.suspensionReason || t('org.adminDetail.suspendedFallbackReason')}
          </p>
        </div>
      ) : null}

      <QuotaAlert allowances={allowances} />

      <Card title={t('org.adminDetail.recordTitle')}>
        <CardFacts>
          <CardFact label={t('org.adminDetail.recordAddress')}>
            <code className="font-mono">/{tenant.slug}</code>
          </CardFact>
          <CardFact label={t('org.adminDetail.recordPlan')}>
            {plan ? (
              <>
                {plan.name} <Badge tone="neutral">{plan.code}</Badge>
              </>
            ) : (
              t('org.adminDetail.recordPlanNone')
            )}
          </CardFact>
          <CardFact label={t('org.adminDetail.recordOpened')}>
            <RelativeTime at={tenant.createdAt} />
          </CardFact>
          <CardFact label={t('org.adminDetail.recordLastChange')}>
            <RelativeTime at={tenant.updatedAt} />
          </CardFact>
        </CardFacts>
      </Card>

      <TenantPlanCard tenant={tenant} plan={plan} plans={plans} />

      <Card
        title={t('org.adminDetail.limitsTitle')}
        description={t('org.adminDetail.limitsDesc')}
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
        title={t('org.adminDetail.peopleTitle')}
        description={
          owners.length === 0
            ? t('org.adminDetail.peopleNoOwners')
            : t('org.adminDetail.peopleDesc', {
                access: accepted.length,
                owners: owners.length,
                ownerWord: t('org.adminDetail.ownerWord', {count: owners.length}),
              })
        }
        padded={false}
      >
        {members.length === 0 ? (
          <p className="px-4 py-4 text-sm text-ink-600 md:px-5 dark:text-ink-400">
            {t('org.adminDetail.peopleEmpty')}
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
                    {member.accepted ? roleLabel(member.role) : t('org.members.invitedBadge')}
                  </Badge>
                </p>
                <p className="mt-0.5 truncate text-xs text-ink-500 dark:text-ink-400">
                  {member.displayName}
                  {member.acceptedAt ? (
                    <>
                      {t('org.adminDetail.peopleJoined')}
                      <RelativeTime at={member.acceptedAt} />
                    </>
                  ) : member.invitedAt ? (
                    <>
                      {t('org.adminDetail.peopleInvited')}
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
