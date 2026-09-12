import {Head, usePage} from '@inertiajs/react'

import {useI18n} from '@/i18n'
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
  const {t} = useI18n()
  const {service, domains, nodeAddress, allowance, viewerRole} =
    usePage<ServiceDomainsProps>().props
  const writable = mayWrite(viewerRole)
  const primary = domains.find((domain) => domain.primary) ?? null
  const attention = domains.filter((domain) => domain.atRisk)

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('domain.page.title', {service: service.name})} />
      <ServiceTabs serviceId={service.id} />

      <PageHeader
        title={t('domain.page.header')}
        description={
          primary
            ? t('domain.page.description.primary', {hostname: primary.hostname})
            : t('domain.page.description.none')
        }
      />

      {nodeAddress === null ? (
        <Card>
          <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
            {t('domain.page.unplacedNotice')}
          </p>
        </Card>
      ) : null}

      {attention.length > 0 ? (
        <Card className="border-degraded/50">
          <p className="text-sm leading-relaxed text-ink-800 dark:text-ink-200">
            {attention.length === domains.length
              ? attention.length === 1
                ? t('domain.page.attention.allSingle')
                : t('domain.page.attention.allMultiple', {count: attention.length})
              : attention.length === 1
                ? t('domain.page.attention.partialSingle', {total: domains.length})
                : t('domain.page.attention.partialMultiple', {count: attention.length, total: domains.length})}{' '}
            {attention.map((domain) => domain.hostname).join(', ')}. {t('domain.page.attention.eachBelow')}
          </p>
        </Card>
      ) : null}

      <AddDomainForm serviceId={service.id} allowance={allowance} writable={writable} />

      {domains.length === 0 ? (
        <Card padded={false}>
          <EmptyState
            icon={<Icon name="external" />}
            title={t('domain.page.empty.title')}
            description={t('domain.page.empty.description')}
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

      <Card title={t('domain.page.quota.title')}>
        <QuotaMeter allowance={allowance} />
        <p className="text-sm text-ink-500 dark:text-ink-400">
          {t('domain.page.quota.description')}
        </p>
      </Card>

      {writable ? null : (
        <p className="px-1 text-sm text-ink-500 dark:text-ink-400">
          {t('domain.page.readOnly')}
        </p>
      )}
    </div>
  )
}
