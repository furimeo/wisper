import {Head, Link, usePage} from '@inertiajs/react'
import {useMemo, useState} from 'react'

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
      <Head title="Tenants" />

      <PageHeader
        title="Tenants"
        description="Every organization on this installation, which plan it is on and whether it is suspended."
      />

      <Card title="Organizations" action={<Badge>{tenants.length}</Badge>} padded={false}>
        <div className="flex flex-col gap-3 px-4 py-3 md:px-5">
          {suspended > 0 ? (
            <Badge tone="failed" dot>
              {suspended} suspended
            </Badge>
          ) : null}

          <Input
            type="search"
            inputMode="search"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            enterKeyHint="search"
            placeholder="Filter by name or address"
            aria-label="Filter tenants"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>

        <DataList
          items={matching}
          keyOf={(tenant) => tenant.id}
          label="tenants"
          href={(tenant) => `/admin/organizations/${tenant.id}`}
          primary={(tenant) => tenant.name}
          secondary={(tenant) => `/${tenant.slug} · ${planNames.get(tenant.planId) ?? 'no plan'}`}
          trailing={(tenant) =>
            tenant.suspended ? <Badge tone="failed">Suspended</Badge> : <Badge tone="running" dot>Active</Badge>
          }
          columns={[
            {
              key: 'name',
              header: 'Organization',
              cell: (tenant) => (
                <Link href={`/admin/organizations/${tenant.id}`} className="font-medium">
                  {tenant.name}
                </Link>
              ),
            },
            {
              key: 'slug',
              header: 'Address',
              cell: (tenant) => <code className="font-mono text-xs">/{tenant.slug}</code>,
            },
            {
              key: 'plan',
              header: 'Plan',
              cell: (tenant) => planNames.get(tenant.planId) ?? 'no plan',
            },
            {
              key: 'created',
              header: 'Opened',
              cell: (tenant) => <RelativeTime at={tenant.createdAt} />,
            },
            {
              key: 'status',
              header: 'State',
              align: 'right',
              cell: (tenant) =>
                tenant.suspended ? (
                  <Badge tone="failed">Suspended</Badge>
                ) : (
                  <Badge tone="running" dot>
                    Active
                  </Badge>
                ),
            },
          ]}
          empty={
            <EmptyState
              icon={<Icon name="organization" />}
              title={query ? 'Nothing matches' : 'No organizations yet'}
              description={
                query
                  ? `No tenant's name or address contains “${query}”. Clear the filter to see all ${tenants.length}.`
                  : 'Nobody has opened one. A customer creates their own from the Organizations screen; there is no operator form for it, because the person who opens a tenant has to end up owning it.'
              }
            />
          }
        />
      </Card>
    </div>
  )
}
