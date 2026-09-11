import type {BadgeTone} from '@/shell'

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

const KIND_LABELS: Record<DomainKind, string> = {
  PRIMARY: 'Main address',
  ALIAS: 'Also points here',
  WILDCARD: 'Wildcard',
}

const TLS_LABELS: Record<DomainTlsMode, string> = {
  ON_DEMAND: 'HTTPS, certificate obtained automatically',
  STATIC: 'HTTPS, certificate obtained automatically',
  OFF: 'Plain HTTP, no certificate',
}

const VERIFICATION_LABELS: Record<DomainVerification, string> = {
  PENDING: 'Checking',
  VERIFIED: 'Verified',
  FAILED: 'Not pointing here',
}

const VERIFICATION_TONES: Record<DomainVerification, BadgeTone> = {
  PENDING: 'neutral',
  VERIFIED: 'running',
  FAILED: 'degraded',
}

const CERTIFICATE_LABELS: Record<CertificateState, string> = {
  PENDING: 'Waiting for a certificate',
  ISSUED: 'Certificate issued',
  RENEWING: 'Renewing',
  FAILED: 'Certificate failed',
  REVOKED: 'Revoked',
}

const CERTIFICATE_TONES: Record<CertificateState, BadgeTone> = {
  PENDING: 'neutral',
  ISSUED: 'running',
  RENEWING: 'accent',
  FAILED: 'failed',
  REVOKED: 'failed',
}

/**
 * One sentence per certificate state, saying what the platform is doing about it.
 *
 * `RENEWING` is the one that reads alarming and is not: the certificate in force is still
 * being served while the node retries ACME, and only a renewal that keeps failing for a
 * fortnight is worth anybody's attention.
 */
const CERTIFICATE_SENTENCES: Record<CertificateState, string> = {
  PENDING:
    'The node will obtain one the first time somebody asks for this hostname over HTTPS. ' +
    'That needs DNS pointing here and port 443 reachable.',
  ISSUED: 'Visitors get HTTPS. The node renews it well before it lapses, without asking.',
  RENEWING:
    'The current certificate is still being served while the node obtains its replacement.',
  FAILED: 'No certificate is in force, so HTTPS to this hostname will not complete.',
  REVOKED: 'This certificate was withdrawn and is not being served.',
}

export function kindLabel(kind: DomainKind): string {
  return KIND_LABELS[kind] ?? kind
}

export function tlsLabel(mode: DomainTlsMode): string {
  return TLS_LABELS[mode] ?? mode
}

export function verificationLabel(state: DomainVerification): string {
  return VERIFICATION_LABELS[state] ?? state
}

export function verificationTone(state: DomainVerification): BadgeTone {
  return VERIFICATION_TONES[state] ?? 'neutral'
}

export function certificateLabel(state: CertificateState): string {
  return CERTIFICATE_LABELS[state] ?? state
}

export function certificateTone(state: CertificateState): BadgeTone {
  return CERTIFICATE_TONES[state] ?? 'neutral'
}

export function certificateSentence(state: CertificateState): string {
  return CERTIFICATE_SENTENCES[state] ?? ''
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
    return 'Nothing is running this service yet, so there is no address to point this hostname at.'
  }
  if (domain.verificationState === 'FAILED') {
    return domain.lastCheckError ?? 'This hostname does not resolve to this service’s node.'
  }
  if (domain.verificationState === 'PENDING') {
    return domain.lastCheckError ?? 'wisper is waiting for DNS to point here. It rechecks on its own.'
  }
  if (!wantsCertificate(domain)) {
    return 'Verified and served over plain HTTP. No certificate is being obtained for it.'
  }
  const certificate = domain.certificate
  if (certificate === null) {
    return 'Verified. The node obtains a certificate the first time somebody asks for it over HTTPS.'
  }
  if (certificate.state === 'FAILED' || certificate.renewalFailureCount > 0) {
    return certificate.lastError ?? 'The node could not obtain a certificate for this hostname.'
  }
  const days = daysUntilExpiry(certificate)
  if (days !== null && days <= EXPIRY_WARNING_DAYS) {
    return days < 0
      ? 'This certificate has expired and HTTPS to this hostname will not complete.'
      : `This certificate expires in ${days} day${days === 1 ? '' : 's'} and has not renewed yet.`
  }
  return domain.serving
    ? 'Verified, served over HTTPS, and the node is answering for it.'
    : 'Verified. The node has not reported serving it yet.'
}
