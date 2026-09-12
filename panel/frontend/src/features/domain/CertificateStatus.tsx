import type {ReactNode} from 'react'

import {useI18n} from '@/i18n'
import {Badge, CopyButton, RelativeTime, cx} from '@/shell'

import type {DomainView} from './domainTypes'
import {
  EXPIRY_WARNING_DAYS,
  certificateLabel,
  certificateSentence,
  certificateTone,
  daysUntilExpiry,
  wantsCertificate,
} from './domainVocabulary'

/**
 * The certificate for one hostname, exactly as the node last reported it.
 *
 * The panel does not own any of this and does not pretend to: the private key never leaves
 * the node, `certificate` holds metadata only, and every value below was written from a
 * status report. Which is why "nothing here yet" is a real state and gets a sentence
 * rather than an empty panel - a node that has not mentioned a certificate has not failed
 * to get one, it has simply not been asked for it yet.
 *
 * `renewalFailureCount` is the number worth looking at. It resets to zero the moment an
 * attempt succeeds, so any value above zero means renewal is failing *now* - and a
 * certificate that is valid for another six weeks while renewal quietly fails is the
 * outage that arrives without warning.
 */
export function CertificateStatus({domain}: {domain: DomainView}) {
  const {t} = useI18n()

  if (!wantsCertificate(domain)) {
    return (
      <p className="text-sm leading-relaxed text-ink-600 dark:text-ink-400">
        {t('domain.cert.tlsOff')}
      </p>
    )
  }

  const certificate = domain.certificate
  if (certificate === null) {
    return (
      <p className="text-sm leading-relaxed text-ink-600 dark:text-ink-400">
        {t('domain.cert.noneYet', {hostname: domain.hostname})}
      </p>
    )
  }

  const days = daysUntilExpiry(certificate)
  const expiring = days !== null && days <= EXPIRY_WARNING_DAYS
  const failing = certificate.state === 'FAILED' || certificate.renewalFailureCount > 0

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Badge
          tone={certificateTone(certificate.state)}
          dot
          pulse={certificate.state === 'RENEWING'}
        >
          {certificateLabel(certificate.state)}
        </Badge>
        {certificate.live ? (
          <span className="text-sm text-ink-600 dark:text-ink-400">
            {t('domain.cert.liveBadge')}
          </span>
        ) : null}
      </div>

      <p className="text-sm leading-relaxed text-ink-600 dark:text-ink-400">
        {certificateSentence(certificate.state)}
      </p>

      {certificate.lastError ? (
        <p
          role="alert"
          className="rounded-lg border border-failed/40 bg-failed/10 px-3 py-2 text-sm leading-relaxed text-ink-900 dark:text-ink-100"
        >
          {certificate.lastError}
        </p>
      ) : null}

      {failing ? (
        <p className="text-sm leading-relaxed text-failed">
          {certificate.renewalFailureCount === 0
            ? t('domain.cert.failedOnce')
            : t('domain.cert.failedTimes', {count: certificate.renewalFailureCount})}
        </p>
      ) : null}

      <dl className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-2">
        <Fact label={t('domain.cert.fact.issuedBy')}>{certificate.issuer ?? t('domain.cert.notReported')}</Fact>

        <Fact label={t('domain.cert.fact.expires')}>
          {certificate.notAfter === null ? (
            t('domain.cert.notReported')
          ) : (
            <span className={cx(expiring ? 'text-failed' : '')}>
              <RelativeTime at={certificate.notAfter} />
              {days === null ? null : (
                <span className="text-ink-500 dark:text-ink-400">
                  {' '}
                  ({days < 0 ? t('domain.cert.expired') : t('domain.cert.daysRemaining', {count: days})})
                </span>
              )}
            </span>
          )}
        </Fact>

        <Fact label={t('domain.cert.fact.obtained')}>
          {certificate.obtainedAt === null ? (
            t('domain.cert.never')
          ) : (
            <RelativeTime at={certificate.obtainedAt} />
          )}
        </Fact>

        <Fact label={t('domain.cert.fact.lastRenewal')}>
          {certificate.lastRenewalAttemptAt === null ? (
            t('domain.cert.noneSinceObtained')
          ) : (
            <RelativeTime at={certificate.lastRenewalAttemptAt} />
          )}
        </Fact>

        {certificate.subjectAlternativeNames.length > 1 ? (
          <Fact label={t('domain.cert.fact.alsoCovers')}>
            {certificate.subjectAlternativeNames
              .filter((name) => name !== domain.hostname)
              .join(', ')}
          </Fact>
        ) : null}

        {certificate.fingerprintSha256 === null ? null : (
          <Fact label={t('domain.cert.fact.fingerprint')}>
            <span className="flex flex-wrap items-center gap-2">
              <code className="font-mono text-xs break-all">
                {certificate.fingerprintSha256}
              </code>
              <CopyButton
                value={certificate.fingerprintSha256}
                describedAs={t('domain.cert.fact.copyFingerprint')}
              />
            </span>
          </Fact>
        )}
      </dl>
    </div>
  )
}

function Fact({label, children}: {label: string; children: ReactNode}) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs font-medium uppercase tracking-wide text-ink-500 dark:text-ink-400">
        {label}
      </dt>
      <dd className="text-sm break-words text-ink-900 dark:text-ink-100">{children}</dd>
    </div>
  )
}
