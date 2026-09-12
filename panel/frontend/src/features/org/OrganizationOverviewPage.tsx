import {Head, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
import {
  Badge,
  ButtonLink,
  Card,
  CardFact,
  CardFacts,
  Icon,
  PageHeader,
  pathOf,
  useCurrentOrganization,
} from '@/shell'
import type {MemberRole} from '@/shell'

import {QuotaAlert} from './QuotaAlert'
import {QuotaMeter} from './QuotaMeter'
import type {Plan, QuotaAllowance} from './orgTypes'
import {roleDescription, roleLabel} from './roleVocabulary'

/**
 * `GET /orgs/{organizationId}` - one tenant: its plan, its limits and who is in it.
 *
 * The organization itself is not a page prop. `CurrentOrganizationProps` puts it on every
 * page as a shared prop, resolved from the `{organizationId}` in this very URL, so reading
 * it from the model as well would be the same row fetched twice with two chances to
 * disagree. The id comes from the address bar for the same reason: it is the most specific
 * thing the visitor said.
 *
 * The limits are drawn in full rather than filtered to the interesting ones. This is the
 * screen somebody opens to find out what their plan actually is, and a list that hides the
 * eight limits nowhere near their ceiling is a list that cannot answer that. What is
 * filtered is the banner above it, which names only what is about to stop working.
 *
 * There is nothing to change here. Moving between plans and granting exceptions are
 * operator decisions and live under `/admin`; membership is the next screen along. A card
 * of disabled buttons would imply the customer is one role away from a control that does
 * not exist for anybody.
 */
type OrganizationOverviewProps = {
  /** Null only if the tenant's plan row was removed underneath it. */
  plan: Plan | null
  allowances: QuotaAllowance[]
  memberCount: number
  viewerRole: MemberRole
}

export default function OrganizationOverviewPage() {
  const page = usePage<OrganizationOverviewProps>()
  const {plan, allowances, memberCount, viewerRole} = page.props
  const organization = useCurrentOrganization()

  // `/orgs/{organizationId}` - the second segment. The shared prop is resolved from the
  // same variable, so this and `organization.id` are the same value; the URL is used
  // because it is the one that cannot be null.
  const organizationId = pathOf(page.url).split('/')[2] ?? ''
  const name = organization?.name ?? t('org.overview.fallbackTitle')

  return (
    <div className="flex flex-col gap-4">
      <Head title={name} />

      <PageHeader
        title={name}
        description={
          organization
            ? t('org.overview.roleDescription', {
                role: roleLabel(viewerRole).toLowerCase(),
                description: roleDescription(viewerRole),
              })
            : t('org.overview.defaultDescription')
        }
        actions={
          <ButtonLink
            href={`/orgs/${organizationId}/members`}
            variant="secondary"
            icon={<Icon name="account" />}
          >
            {t('org.overview.membersBtn')}
          </ButtonLink>
        }
      />

      {organization?.suspended ? (
        <div className="rounded-xl border border-failed/40 bg-failed/10 px-4 py-3.5 md:px-5">
          <p className="text-sm font-semibold text-ink-900 dark:text-ink-100">
            {t('org.overview.suspendedTitle')}
          </p>
          <p className="mt-1 text-sm leading-relaxed text-ink-800 dark:text-ink-200">
            {organization.suspensionReason || t('org.overview.suspendedFallbackReason')}
          </p>
          <p className="mt-1 text-sm text-ink-600 dark:text-ink-400">
            {t('org.overview.suspendedNotice')}
          </p>
        </div>
      ) : null}

      <QuotaAlert allowances={allowances} />

      <Card title={t('org.overview.planCardTitle')} action={plan ? <Badge tone="accent">{plan.code}</Badge> : null}>
        {plan ? (
          <>
            <CardFacts>
              <CardFact label={t('org.overview.planTier')}>{plan.name}</CardFact>
              <CardFact label={t('org.overview.planMembers')}>
                {t('org.overview.memberCount', {count: memberCount})}
              </CardFact>
              {organization ? (
                <CardFact label={t('org.overview.planAddress')}>
                  <code className="font-mono">/{organization.slug}</code>
                </CardFact>
              ) : null}
              <CardFact label={t('org.overview.planYourRole')}>{roleLabel(viewerRole)}</CardFact>
            </CardFacts>
            {plan.description ? (
              <p className="mt-2 text-sm leading-relaxed text-ink-600 dark:text-ink-400">
                {plan.description}
              </p>
            ) : null}
          </>
        ) : (
          <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
            {t('org.overview.noPlan')}
          </p>
        )}
        <p className="mt-3 text-sm text-ink-500 dark:text-ink-400">
          {t('org.overview.planFooter')}
        </p>
      </Card>

      <Card
        title={t('org.overview.limitsTitle')}
        description={t('org.overview.limitsDescription')}
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

      <Card
        title={t('org.overview.peopleTitle')}
        description={t('org.overview.peopleDesc', {count: memberCount})}
        footer={
          <ButtonLink
            href={`/orgs/${organizationId}/members`}
            variant="secondary"
            block
            className="sm:w-auto"
          >
            {t('org.overview.manageMembers')}
          </ButtonLink>
        }
      >
        <p className="text-sm leading-relaxed text-ink-600 dark:text-ink-400">
          {t('org.overview.peopleNote')}
        </p>
      </Card>
    </div>
  )
}
