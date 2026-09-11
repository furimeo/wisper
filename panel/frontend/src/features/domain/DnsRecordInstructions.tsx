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
  if (domain.verified) {
    return (
      <p className="flex items-start gap-2 text-sm text-ink-600 dark:text-ink-400">
        <Icon name="check" className="mt-0.5 size-4 shrink-0 text-running" />
        <span>
          Ownership was proved <RelativeTime at={domain.verifiedAt} />. wisper does not
          re-check a verified hostname, so moving DNS during a migration will not switch
          HTTPS off underneath a working site.
        </span>
      </p>
    )
  }

  if (nodeAddress === null) {
    return (
      <div className="rounded-lg border border-ink-200 bg-ink-50 p-3 dark:border-ink-800 dark:bg-ink-950">
        <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
          No node is holding this service yet, so there is no address to point{' '}
          <strong className="font-medium">{domain.hostname}</strong> at. That is not
          something you can fix from here - deploy the service, or ask an operator whether
          the platform has room. wisper starts checking on its own as soon as it is placed.
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-3">
      <LastCheck domain={domain} />

      <Record
        title="Point the hostname here"
        note="The usual way, and the one that also makes the site reachable."
        type={recordType(nodeAddress)}
        name={domain.hostname}
        value={nodeAddress}
      />

      {domain.verificationToken === null ? null : (
        <Record
          title="Or prove ownership without moving traffic"
          note="For a hostname still serving a live site elsewhere. wisper also accepts this record one level up, at the parent name, for DNS panels that will not create it here."
          type="TXT"
          name={domain.challengeRecordName}
          value={domain.challengeRecordValue}
        />
      )}

      <p className="text-sm text-ink-500 dark:text-ink-400">
        Some DNS panels want the name relative to the zone rather than in full - in that
        case enter only the part before your registered domain, or <code>@</code> for the
        domain itself. Changes can take a few minutes to propagate; wisper rechecks on its
        own and the button above forces one now.
      </p>
    </div>
  )
}

/** What the last lookup actually found, which is the whole point of storing it. */
function LastCheck({domain}: {domain: DomainView}) {
  if (domain.lastCheckError === null && domain.lastCheckedAt === null) {
    return (
      <p className="text-sm text-ink-600 dark:text-ink-400">
        Nothing has been checked yet. wisper looks this hostname up shortly after it is
        added.
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
        {domain.lastCheckError ?? 'The last check reached no conclusion.'}
      </p>
      {domain.lastCheckedAt === null ? null : (
        <p className="mt-1 text-xs text-ink-500 dark:text-ink-400">
          Checked <RelativeTime at={domain.lastCheckedAt} />
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
  return (
    <div className="rounded-lg border border-ink-200 p-3 dark:border-ink-800">
      <h4 className="text-sm font-medium text-ink-900 dark:text-ink-100">{title}</h4>
      <p className="mt-0.5 mb-3 text-sm text-ink-500 dark:text-ink-400">{note}</p>

      <dl className="flex flex-col gap-2">
        <Field label="Type" value={type} copyable={false} />
        <Field label="Name" value={name} copyable />
        <Field label="Value" value={value} copyable />
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
          <CopyButton value={value} describedAs={`Copy the record ${label.toLowerCase()}`} />
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
