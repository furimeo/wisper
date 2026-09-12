import {useI18n} from '@/i18n'
import {Badge} from '@/shell'

import type {DomainView} from './domainTypes'
import {verificationLabel, verificationTone, wantsCertificate} from './domainVocabulary'

/**
 * One pill per hostname, answering the only question the list is scanned for: is this
 * address working.
 *
 * There are three states underneath it - verification, the certificate, and whether the
 * node reports the route as loaded - and showing three pills per row would mean reading
 * nine of them to find the one that is wrong. So they collapse in the order a customer has
 * to act on them: DNS first, because a certificate cannot be obtained without it, and the
 * edge cannot serve without one.
 */
export function DomainStateBadge({domain}: {domain: DomainView}) {
  const {t} = useI18n()

  if (!domain.verified) {
    return (
      <Badge
        tone={verificationTone(domain.verificationState)}
        dot
        pulse={domain.verificationState === 'PENDING'}
      >
        {verificationLabel(domain.verificationState)}
      </Badge>
    )
  }

  if (!wantsCertificate(domain)) {
    return (
      <Badge tone="neutral" dot>
        {t('domain.badge.httpOnly')}
      </Badge>
    )
  }

  const certificate = domain.certificate
  if (certificate !== null && (certificate.state === 'FAILED' || certificate.renewalFailureCount > 0)) {
    return (
      <Badge tone="failed" dot>
        {t('domain.badge.certFailing')}
      </Badge>
    )
  }

  if (domain.serving) {
    return (
      <Badge tone="running" dot>
        {t('domain.badge.live')}
      </Badge>
    )
  }

  return (
    <Badge tone="accent" dot pulse>
      {t('domain.badge.waitingNode')}
    </Badge>
  )
}
