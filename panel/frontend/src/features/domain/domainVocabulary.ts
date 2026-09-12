import type {BadgeTone} from '@/shell'
import {t} from '@/i18n'

import type {
  CertificateState,
  CertificateView,
  DomainKind,
  DomainTlsMode,
  DomainVerification,
  DomainView,
} from './domainTypes'

/**
 * What each `domain` enum is called on screen, and the handful of judgements the Java
 * records do not send.
 *
 * The words match the server's own sentences where the two land next to each other - a
 * flash message written by `DomainController` and a pill written here often end up in the
 * same glance, and two names for one state is a support ticket.
 */

const VERIFICATION_TONES: Record<DomainVerification, BadgeTone> = {
  PENDING: 'neutral',
  VERIFIED: 'running',
  FAILED: 'degraded',
}

const CERTIFICATE_TONES: Record<CertificateState, BadgeTone> = {
  PENDING: 'neutral',
  ISSUED: 'running',
  RENEWING: 'accent',
  FAILED: 'failed',
  REVOKED: 'failed',
}

export function kindLabel(kind: DomainKind): string {
  switch (kind) {
    case 'PRIMARY':
      return t('domain.kind.primary')
    case 'ALIAS':
      return t('domain.kind.alias')
    case 'WILDCARD':
      return t('domain.kind.wildcard')
    default:
      return kind
  }
}

export function tlsLabel(mode: DomainTlsMode): string {
  switch (mode) {
    case 'ON_DEMAND':
      return t('domain.tls.onDemand')
    case 'STATIC':
      return t('domain.tls.static')
    case 'OFF':
      return t('domain.tls.off')
    default:
      return mode
  }
}

export function verificationLabel(state: DomainVerification): string {
  switch (state) {
    case 'PENDING':
      return t('domain.verification.pending')
    case 'VERIFIED':
      return t('domain.verification.verified')
    case 'FAILED':
      return t('domain.verification.failed')
    default:
      return state
  }
}

export function verificationTone(state: DomainVerification): BadgeTone {
  return VERIFICATION_TONES[state] ?? 'neutral'
}

export function certificateLabel(state: CertificateState): string {
  switch (state) {
    case 'PENDING':
      return t('domain.certificate.pending')
    case 'ISSUED':
      return t('domain.certificate.issued')
    case 'RENEWING':
      return t('domain.certificate.renewing')
    case 'FAILED':
      return t('domain.certificate.failed')
    case 'REVOKED':
      return t('domain.certificate.revoked')
    default:
      return state
  }
}

export function certificateTone(state: CertificateState): BadgeTone {
  return CERTIFICATE_TONES[state] ?? 'neutral'
}

export function certificateSentence(state: CertificateState): string {
  switch (state) {
    case 'PENDING':
      return t('domain.certificate.sentence.pending')
    case 'ISSUED':
      return t('domain.certificate.sentence.issued')
    case 'RENEWING':
      return t('domain.certificate.sentence.renewing')
    case 'FAILED':
      return t('domain.certificate.sentence.failed')
    case 'REVOKED':
      return t('domain.certificate.sentence.revoked')
    default:
      return ''
  }
}

/** Whether the panel wants a certificate for this hostname at all. Mirrors `wantsCertificate()`. */
export function wantsCertificate(domain: DomainView): boolean {
  return domain.tlsMode !== 'OFF'
}

/** The address to open in a browser. HTTP while TLS is off, because HTTPS would not answer. */
export function domainUrl(domain: DomainView): string {
  return `${domain.tlsMode === 'OFF' ? 'http' : 'https'}://${domain.hostname}`
}

/** Days until the certificate lapses, or null when there is nothing with an expiry. */
export function daysUntilExpiry(
  certificate: CertificateView | null,
  now = Date.now(),
): number | null {
  if (certificate === null || certificate.notAfter === null) {
    return null
  }
  const at = Date.parse(certificate.notAfter)
  if (!Number.isFinite(at)) {
    return null
  }
  return Math.floor((at - now) / 86_400_000)
}

/**
 * Inside this many days of expiry, an unrenewed certificate is worth saying something
 * about. Let's Encrypt certificates last ninety days and the node starts renewing at
 * thirty, so anything still unrenewed at fourteen has failed at it several times.
 */
export const EXPIRY_WARNING_DAYS = 14

/**
 * The single sentence at the top of a hostname's card: what is wrong, or that nothing is.
 *
 * Ordered by what a customer has to do about it, not by severity in the abstract. An
 * unverified hostname is first because every other problem downstream of it is a
 * consequence, and telling somebody their certificate failed when their DNS was never
 * pointed here sends them to fix the wrong thing.
 */
export function domainHeadline(domain: DomainView, placed: boolean): string {
  if (!placed) {
    return t('domain.headline.notPlaced')
  }
  if (domain.verificationState === 'FAILED') {
    return domain.lastCheckError ?? t('domain.headline.failedDefault')
  }
  if (domain.verificationState === 'PENDING') {
    return domain.lastCheckError ?? t('domain.headline.pendingDefault')
  }
  if (!wantsCertificate(domain)) {
    return t('domain.headline.noTls')
  }
  const certificate = domain.certificate
  if (certificate === null) {
    return t('domain.headline.noCertYet')
  }
  if (certificate.state === 'FAILED' || certificate.renewalFailureCount > 0) {
    return certificate.lastError ?? t('domain.headline.certFailedDefault')
  }
  const days = daysUntilExpiry(certificate)
  if (days !== null && days <= EXPIRY_WARNING_DAYS) {
    return days < 0
      ? t('domain.headline.certExpired')
      : t('domain.headline.certExpiresSoon', {count: days})
  }
  return domain.serving
    ? t('domain.headline.serving')
    : t('domain.headline.notServingYet')
}
