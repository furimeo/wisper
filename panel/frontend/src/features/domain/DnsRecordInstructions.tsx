import {useI18n} from '@/i18n'
import {CopyButton, Icon, RelativeTime} from '@/shell'

import type {DomainView} from './domainTypes'

/**
 * Exactly which DNS record has to exist, with the exact strings to paste into it.
 *
 * This is the part of the panel that decides whether a customer gets their domain working
 * or gives up. "Point your domain at us" is not instructions; a record type, a name and a
 * value they can copy is. Both accepted proofs are shown, in the order most people need
 * them:
 *
 * 1. **An address record.** The ordinary case, and the one that also makes the site
 *    actually reachable - verification is a side effect of doing the thing they came to do.
 * 2. **A TXT challenge.** For a hostname still serving a live site somewhere else, which
 *    cannot be repointed until the replacement is ready. That is the migration this
 *    platform exists to make possible, so it is on the screen rather than in a manual.
 *
 * The record name and value for the challenge come from the server, never from here. If
 * this page said one string and `VerifyDomainOwnership` asked DNS for another, the check
 * would fail for somebody who did exactly what they were told, and it would look like a
 * DNS problem.
 */
export function DnsRecordInstructions({
  domain,
  nodeAddress,
}: {
  domain: DomainView
  /** `node.public_address` of the service's active placement, or null when nothing holds it. */
  nodeAddress: string | null
}) {
  const {t} = useI18n()

  if (domain.verified) {
    return (
      <p className="flex items-start gap-2 text-sm text-ink-600 dark:text-ink-400">
        <Icon name="check" className="mt-0.5 size-4 shrink-0 text-running" />
        <span>
          {t('domain.dns.verifiedPrefix')}
          <RelativeTime at={domain.verifiedAt} />
          {t('domain.dns.verifiedSuffix')}
        </span>
      </p>
    )
  }

  if (nodeAddress === null) {
    return (
      <div className="rounded-lg border border-ink-200 bg-ink-50 p-3 dark:border-ink-800 dark:bg-ink-950">
        <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
          {t('domain.dns.unplacedNotice', {hostname: domain.hostname})}
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-3">
      <LastCheck domain={domain} />

      <Record
        title={t('domain.dns.record.pointHere.title')}
        note={t('domain.dns.record.pointHere.note')}
        type={recordType(nodeAddress)}
        name={domain.hostname}
        value={nodeAddress}
      />

      {domain.verificationToken === null ? null : (
        <Record
          title={t('domain.dns.record.txtChallenge.title')}
          note={t('domain.dns.record.txtChallenge.note')}
          type="TXT"
          name={domain.challengeRecordName}
          value={domain.challengeRecordValue}
        />
      )}

      <p className="text-sm text-ink-500 dark:text-ink-400">
        {t('domain.dns.propagationNotice')}
      </p>
    </div>
  )
}

/** What the last lookup actually found, which is the whole point of storing it. */
function LastCheck({domain}: {domain: DomainView}) {
  const {t} = useI18n()

  if (domain.lastCheckError === null && domain.lastCheckedAt === null) {
    return (
      <p className="text-sm text-ink-600 dark:text-ink-400">
        {t('domain.dns.notCheckedYet')}
      </p>
    )
  }
  return (
    <div
      className={
        domain.verificationState === 'FAILED'
          ? 'rounded-lg border border-degraded/40 bg-degraded/10 p-3'
          : 'rounded-lg border border-ink-200 bg-ink-50 p-3 dark:border-ink-800 dark:bg-ink-950'
      }
    >
      <p className="text-sm leading-relaxed text-ink-800 dark:text-ink-200">
        {domain.lastCheckError ?? t('domain.dns.noConclusion')}
      </p>
      {domain.lastCheckedAt === null ? null : (
        <p className="mt-1 text-xs text-ink-500 dark:text-ink-400">
          {t('domain.dns.checkedPrefix')}
          <RelativeTime at={domain.lastCheckedAt} />
        </p>
      )}
    </div>
  )
}

/** One record, in the three fields every DNS panel asks for. */
function Record({
  title,
  note,
  type,
  name,
  value,
}: {
  title: string
  note: string
  type: string
  name: string
  value: string
}) {
  const {t} = useI18n()

  return (
    <div className="rounded-lg border border-ink-200 p-3 dark:border-ink-800">
      <h4 className="text-sm font-medium text-ink-900 dark:text-ink-100">{title}</h4>
      <p className="mt-0.5 mb-3 text-sm text-ink-500 dark:text-ink-400">{note}</p>

      <dl className="flex flex-col gap-2">
        <Field label={t('domain.dns.field.type')} value={type} copyable={false} />
        <Field label={t('domain.dns.field.name')} value={name} copyable />
        <Field label={t('domain.dns.field.value')} value={value} copyable />
      </dl>
    </div>
  )
}

function Field({
  label,
  value,
  copyable,
}: {
  label: string
  value: string
  copyable: boolean
}) {
  const {t} = useI18n()
  // The label sits above rather than beside the value: at 375px a label column plus a
  // 44px copy button leaves the value about a hundred and eighty pixels, which is not
  // enough to see a hostname or a base64 token without scrolling it sideways first.
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-xs font-medium uppercase tracking-wide text-ink-500 dark:text-ink-400">
        {label}
      </dt>
      <dd className="flex items-center gap-2">
        <code className="min-w-0 flex-1 overflow-x-auto rounded bg-ink-100 px-2 py-2 font-mono text-xs whitespace-nowrap text-ink-900 dark:bg-ink-800 dark:text-ink-100">
          {value}
        </code>
        {copyable ? (
          <CopyButton value={value} describedAs={t('domain.dns.field.copy', {field: label.toLowerCase()})} />
        ) : null}
      </dd>
    </div>
  )
}

/**
 * `node.public_address` holds either a literal address or a name, and the record a
 * customer has to create differs for each.
 */
function recordType(address: string): 'A' | 'AAAA' | 'CNAME' {
  if (address.includes(':')) {
    return 'AAAA'
  }
  return /^\d{1,3}(\.\d{1,3}){3}$/.test(address) ? 'A' : 'CNAME'
}
