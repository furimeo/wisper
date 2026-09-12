import {Head, Link, usePage} from '@inertiajs/react'
import {useMemo, useState} from 'react'

import {t} from '@/i18n'
import {Badge, Card, DataList, EmptyState, Icon, Input, PageHeader, RelativeTime} from '@/shell'

import type {Organization, Plan} from './orgTypes'

/**
 * `GET /admin/organizations` - every tenant on this installation.
 *
 * Gated on `ROLE_ADMIN` by `SecurityConfig`. Nothing here repeats that check: a second copy
 * of an authorization rule is a second place for it to be wrong.
 *
 * The filter is client-side and stays that way. The whole list arrives in the props - this
 * is a self-hosted panel, so the number of tenants is a number an operator could count -
 * and filtering here is instant and needs no round trip that would have to be authorized
 * again.
 *
 * Suspended tenants are counted at the top rather than sorted to it. An operator scanning
 * for one they suspended last week wants it where the name puts it; an operator asking
 * "how many are suspended" wants a number, and those are two different questions.
 */
type AdminOrganizationListProps = {
  /** Newest first, as `findAllNewestFirst()` returned them. */
  tenants: Organization[]
  /** Every plan, selectable ones first, for reading a tenant's tier off its `planId`. */
  plans: Plan[]
}

export default function AdminOrganizationListPage() {
  const {tenants, plans} = usePage<AdminOrganizationListProps>().props
  const [query, setQuery] = useState('')

  const planNames = useMemo(() => {
    const names = new Map<string, string>()
    for (const plan of plans) {
      names.set(plan.id, plan.code)
    }
    return names
  }, [plans])

  const matching = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!needle) {
      return tenants
    }
    return tenants.filter(
      (tenant) =>
        tenant.name.toLowerCase().includes(needle) || tenant.slug.toLowerCase().includes(needle),
    )
  }, [tenants, query])

  const suspended = tenants.filter((tenant) => tenant.suspended).length

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('org.adminTenants.title')} />

      <PageHeader
        title={t('org.adminTenants.title')}
        description={t('org.adminTenants.description')}
      />

      <Card title={t('org.adminTenants.cardTitle')} action={<Badge>{tenants.length}</Badge>} padded={false}>
        <div className="flex flex-col gap-3 px-4 py-3 md:px-5">
          {suspended > 0 ? (
            <Badge tone="failed" dot>
              {t('org.adminTenants.suspendedBadge', {count: suspended})}
            </Badge>
          ) : null}

          <Input
            type="search"
            inputMode="search"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            enterKeyHint="search"
            placeholder={t('org.adminTenants.filterPlaceholder')}
            aria-label={t('org.adminTenants.filterAria')}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>

        <DataList
          items={matching}
          keyOf={(tenant) => tenant.id}
          label={t('org.adminTenants.dataListLabel')}
          href={(tenant) => `/admin/organizations/${tenant.id}`}
          primary={(tenant) => tenant.name}
          secondary={(tenant) => `/${tenant.slug} · ${planNames.get(tenant.planId) ?? t('org.adminTenants.noPlan')}`}
          trailing={(tenant) =>
            tenant.suspended ? (
              <Badge tone="failed">{t('org.adminTenants.badgeSuspended')}</Badge>
            ) : (
              <Badge tone="running" dot>{t('org.adminTenants.badgeActive')}</Badge>
            )
          }
          columns={[
            {
              key: 'name',
              header: t('org.adminTenants.colOrg'),
              cell: (tenant) => (
                <Link href={`/admin/organizations/${tenant.id}`} className="font-medium">
                  {tenant.name}
                </Link>
              ),
            },
            {
              key: 'slug',
              header: t('org.adminTenants.colAddress'),
              cell: (tenant) => <code className="font-mono text-xs">/{tenant.slug}</code>,
            },
            {
              key: 'plan',
              header: t('org.adminTenants.colPlan'),
              cell: (tenant) => planNames.get(tenant.planId) ?? t('org.adminTenants.noPlan'),
            },
            {
              key: 'created',
              header: t('org.adminTenants.colOpened'),
              cell: (tenant) => <RelativeTime at={tenant.createdAt} />,
            },
            {
              key: 'status',
              header: t('org.adminTenants.colState'),
              align: 'right',
              cell: (tenant) =>
                tenant.suspended ? (
                  <Badge tone="failed">{t('org.adminTenants.badgeSuspended')}</Badge>
                ) : (
                  <Badge tone="running" dot>
                    {t('org.adminTenants.badgeActive')}
                  </Badge>
                ),
            },
          ]}
          empty={
            <EmptyState
              icon={<Icon name="organization" />}
              title={query ? t('org.adminTenants.emptyQueryTitle') : t('org.adminTenants.emptyTitle')}
              description={
                query
                  ? t('org.adminTenants.emptyQueryDesc', {query, total: tenants.length})
                  : t('org.adminTenants.emptyDesc')
              }
            />
          }
        />
      </Card>
    </div>
  )
}
