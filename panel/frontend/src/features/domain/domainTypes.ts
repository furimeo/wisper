/**
 * The `domain` package's records, as they arrive on a page.
 *
 * Every interface here is one Java record in `lhqm.furimeo.wisper.domain`, components in
 * declaration order, derived accessors marked. `docs/contracts/pages.md` §4 and §5 are the
 * source: a field that is not there is not sent, and adding one here does not make it
 * appear.
 *
 * Two shapes here go beyond what pages.md sketched, because `DomainView` grew the two
 * strings the screen cannot correctly invent: `challengeRecordName` and
 * `challengeRecordValue`. They are produced by `VerificationToken` on the server and
 * nowhere else, deliberately - if this page told a customer one string and the lookup
 * asked DNS for another, verification would fail for somebody who did exactly what they
 * were told, and it would look like a DNS problem.
 */

/** `domain.DomainKind`. At most one `PRIMARY` per service, kept so by a partial index. */
export type DomainKind = 'PRIMARY' | 'ALIAS' | 'WILDCARD'

/**
 * `domain.DomainTlsMode`. How the node's embedded Caddy should serve the hostname.
 *
 * `STATIC` is reserved for an uploaded certificate and behaves exactly as `ON_DEMAND` in
 * v1 - the node owns every certificate and there is nowhere to upload one. No screen
 * offers it; a row that already carries it still has to render.
 */
export type DomainTlsMode = 'ON_DEMAND' | 'STATIC' | 'OFF'

/** `domain.DomainVerification`. Whether DNS has been seen pointing at the right node. */
export type DomainVerification = 'PENDING' | 'VERIFIED' | 'FAILED'

/** `domain.CertificateState`. Written from the node's reports and from nowhere else. */
export type CertificateState = 'PENDING' | 'ISSUED' | 'RENEWING' | 'FAILED' | 'REVOKED'

/**
 * `domain.CertificateView`. What the node last said about one hostname's certificate.
 *
 * `renewalFailureCount` goes back to zero the moment an attempt succeeds, so any number
 * here means "failing now" rather than "failed once in March". `lastError` is the ACME
 * failure verbatim and is not summarised: "DNS does not point here yet" and "rate limited"
 * need completely different things from the customer.
 */
export interface CertificateView {
  state: CertificateState
  issuer: string | null
  subjectCommonName: string | null
  subjectAlternativeNames: string[]
  fingerprintSha256: string | null
  notBefore: string | null
  notAfter: string | null
  obtainedAt: string | null
  lastRenewalAttemptAt: string | null
  renewalFailureCount: number
  lastError: string | null
  nodeId: string | null
  /** Derived from `isLive()`: `ISSUED` or `RENEWING` - the one visitors are served. */
  live: boolean
}

/**
 * `domain.DomainView`. One hostname pointed at a service.
 *
 * `certificate` is the live row, or the most recent attempt when there is no live one. A
 * hostname whose issuance keeps failing has nothing live, and the error on its last
 * attempt is the entire reason somebody opened this page.
 *
 * `serving` is derived on the server from the placement and the certificate, because there
 * is no column for it: `domain` is panel-owned and `certificate` is the only node-written
 * table keyed by a hostname. It answers "is the edge actually answering for this name".
 */
export interface DomainView {
  id: string
  serviceId: string
  hostname: string
  kind: DomainKind
  tlsMode: DomainTlsMode

  verificationState: DomainVerification
  /** The random half of the TXT challenge. Null only for a row written before it existed. */
  verificationToken: string | null
  verifiedAt: string | null
  lastCheckedAt: string | null
  /** What the last check actually found, in one sentence, ready to show. */
  lastCheckError: string | null

  redirectToHostname: string | null
  forceHttps: boolean
  targetPort: number | null
  createdAt: string

  certificate: CertificateView | null
  serving: boolean
  /** `_wisper-challenge.<hostname>` - where the TXT record goes. */
  challengeRecordName: string
  /** `wisper-domain-verification=<token>` - what goes in it. */
  challengeRecordValue: string

  /** Derived from `isPrimary()`, `isVerified()`, `isAtRisk()`. */
  primary: boolean
  verified: boolean
  atRisk: boolean
}
