import {useState} from 'react'

import {Badge, Button, ButtonLink, Card, Icon, cx} from '@/shell'

import {CertificateStatus} from './CertificateStatus'
import {DnsRecordInstructions} from './DnsRecordInstructions'
import {DomainStateBadge} from './DomainStateBadge'
import {checkDomainNow, makePrimaryDomain, removeDomain} from './domainActions'
import type {DomainView} from './domainTypes'
import {domainHeadline, domainUrl, kindLabel, tlsLabel} from './domainVocabulary'

/**
 * One hostname: what state it is in, what to do about it, and everything underneath.
 *
 * The card opens showing a sentence and two or three buttons, because that is all a
 * hostname that works needs. The DNS records and the certificate are behind a disclosure -
 * except when something is wrong, where the card opens itself. A customer whose domain is
 * broken should not have to discover that there is more to tap; a customer whose four
 * domains all work should not have to scroll past four sets of DNS instructions.
 */
export function DomainCard({
  domain,
  serviceId,
  nodeAddress,
  writable,
}: {
  domain: DomainView
  serviceId: string
  nodeAddress: string | null
  writable: boolean
}) {
  const [open, setOpen] = useState(domain.atRisk)
  const detailsId = `domain-${domain.id}-details`

  return (
    <Card
      className={cx(domain.atRisk ? 'border-degraded/50' : '')}
      title={
        <span className="flex min-w-0 flex-wrap items-center gap-2">
          <span className="truncate font-mono text-sm">{domain.hostname}</span>
          {domain.primary ? <Badge tone="accent">{kindLabel('PRIMARY')}</Badge> : null}
        </span>
      }
      action={<DomainStateBadge domain={domain} />}
    >
      <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
        {domainHeadline(domain, nodeAddress !== null)}
      </p>

      <p className="mt-1 text-sm text-ink-500 dark:text-ink-400">
        {tlsLabel(domain.tlsMode)}
        {domain.forceHttps && domain.tlsMode !== 'OFF'
          ? domain.verified
            ? ' · plain HTTP is redirected'
            : ' · the HTTP redirect stays off until this hostname is verified'
          : ''}
        {domain.targetPort === null ? '' : ` · sent to port ${domain.targetPort}`}
        {domain.redirectToHostname === null
          ? ''
          : ` · permanently redirected to ${domain.redirectToHostname}`}
      </p>

      <div className="mt-3 flex flex-wrap gap-2">
        <ButtonLink
          href={domainUrl(domain)}
          target="_blank"
          rel="noopener noreferrer"
          variant="secondary"
          icon={<Icon name="external" className="size-4" />}
        >
          Open
        </ButtonLink>

        {writable && !domain.verified ? (
          <Button onClick={() => checkDomainNow(serviceId, domain)}>
            Check now
          </Button>
        ) : null}

        <Button
          variant="ghost"
          onClick={() => setOpen((current) => !current)}
          aria-expanded={open}
          aria-controls={detailsId}
          icon={
            <Icon
              name="chevronDown"
              className={cx('size-4 transition-transform', open ? 'rotate-180' : '')}
            />
          }
        >
          {open ? 'Hide details' : 'DNS and certificate'}
        </Button>
      </div>

      {open ? (
        <div id={detailsId} className="mt-4 flex flex-col gap-4 border-t border-ink-200 pt-4 dark:border-ink-800">
          <section>
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-500 dark:text-ink-400">
              DNS
            </h3>
            <DnsRecordInstructions domain={domain} nodeAddress={nodeAddress} />
          </section>

          <section>
            <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-500 dark:text-ink-400">
              Certificate
            </h3>
            <CertificateStatus domain={domain} />
          </section>

          {writable ? (
            <section className="flex flex-wrap gap-2 border-t border-ink-200 pt-4 dark:border-ink-800">
              {domain.primary || domain.kind === 'WILDCARD' ? null : (
                <Button
                  variant="secondary"
                  onClick={() => makePrimaryDomain(serviceId, domain)}
                >
                  Make this the main address
                </Button>
              )}
              <Button
                variant="danger"
                onClick={() => void removeDomain(serviceId, domain)}
              >
                Remove {domain.hostname}
              </Button>
            </section>
          ) : null}
        </div>
      ) : null}
    </Card>
  )
}
