import {Head, usePage} from '@inertiajs/react'

import {Card, EmptyState, Icon, PageHeader, mayWrite} from '@/shell'
import type {MemberRole} from '@/shell'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'
import {ServiceTabs} from '@/features/service/ServiceTabs'
import type {ServiceView} from '@/features/service/serviceTypes'

import {AddDomainForm} from './AddDomainForm'
import {DomainCard} from './DomainCard'
import type {DomainView} from './domainTypes'

/**
 * `GET /services/{serviceId}/domains` - every hostname pointed at one service.
 *
 * The page has one job beyond listing: when a domain is not working, say which record is
 * missing and what to put in it. That is where customers get stuck, and "verification
 * failed" is not an instruction. So every unverified hostname carries the exact record
 * type, name and value, and the sentence saying what the resolver actually returned last
 * time it was asked.
 *
 * `nodeAddress` is the address DNS has to point at, taken from the service's active
 * placement. It is null when nothing is holding the service, and that is a real state
 * rather than a missing value - there is genuinely no address to give yet, and telling
 * somebody to point DNS at nothing would be worse than telling them to wait.
 */
type ServiceDomainsProps = {
  service: ServiceView
  domains: DomainView[]
  nodeAddress: string | null
  allowance: QuotaAllowance
  viewerRole: MemberRole
}

export default function ServiceDomainsPage() {
  const {service, domains, nodeAddress, allowance, viewerRole} =
    usePage<ServiceDomainsProps>().props
  const writable = mayWrite(viewerRole)
  const primary = domains.find((domain) => domain.primary) ?? null
  const attention = domains.filter((domain) => domain.atRisk)

  return (
    <div className="flex flex-col gap-4">
      <Head title={`Domains · ${service.name}`} />
      <ServiceTabs serviceId={service.id} />

      <PageHeader
        title="Domains"
        description={
          primary
            ? `This service answers at ${primary.hostname}. Any other hostname you add points at the same thing.`
            : 'No hostname points at this service yet. Add one and wisper will verify it and obtain a certificate for it.'
        }
      />

      {nodeAddress === null ? (
        <Card>
          <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
            No node is holding this service, so there is no address for DNS to point at and
            nothing is being served. Hostnames added now are kept and checked as soon as the
            service is placed - deploy it, or start it from its overview, and this page will
            fill in the records to create.
          </p>
        </Card>
      ) : null}

      {attention.length > 0 ? (
        <Card className="border-degraded/50">
          <p className="text-sm leading-relaxed text-ink-800 dark:text-ink-200">
            {attention.length === domains.length
              ? attention.length === 1
                ? 'This hostname is not working yet.'
                : `None of these ${attention.length} hostnames is working yet.`
              : `${attention.length} of these ${domains.length} hostnames ${
                  attention.length === 1 ? 'needs' : 'need'
                } attention:`}{' '}
            {attention.map((domain) => domain.hostname).join(', ')}. Each one below says
            which record is missing.
          </p>
        </Card>
      ) : null}

      <AddDomainForm serviceId={service.id} allowance={allowance} writable={writable} />

      {domains.length === 0 ? (
        <Card padded={false}>
          <EmptyState
            icon={<Icon name="external" />}
            title="No hostnames yet"
            description={
              'Every hostname pointed at this service will be listed here with its ' +
              'verification state, the DNS record it still needs, and what the node last ' +
              'said about its certificate. Add one above to start.'
            }
          />
        </Card>
      ) : (
        domains.map((domain) => (
          <DomainCard
            key={domain.id}
            domain={domain}
            serviceId={service.id}
            nodeAddress={nodeAddress}
            writable={writable}
          />
        ))
      )}

      <Card title="Hostnames on this plan">
        <QuotaMeter allowance={allowance} />
        <p className="text-sm text-ink-500 dark:text-ink-400">
          Counted across every service in the organization. A hostname is unique across the
          whole platform, so removing one here releases the name for anybody to claim.
        </p>
      </Card>

      {writable ? null : (
        <p className="px-1 text-sm text-ink-500 dark:text-ink-400">
          You have read access to this organization, so adding, verifying and removing
          hostnames are off. Everything on this page is still readable.
        </p>
      )}
    </div>
  )
}
