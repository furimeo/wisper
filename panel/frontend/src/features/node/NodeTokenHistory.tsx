import {router} from '@inertiajs/react'
import {useState} from 'react'

import {Badge, Button, Card, EmptyState, Icon, RelativeTime, askConfirmation} from '@/shell'

import type {EnrolmentTokenView} from './nodeTypes'
import {tokenStateLabel, tokenStateTone} from './nodeVocabulary'

/**
 * Every bootstrap token ever issued for this record, newest first.
 *
 * The history is the point, and it is why this is not just "is there a live token". An
 * expired token, a token spent from an address nobody recognises, and a token reissued
 * after a failed install are three different situations that look identical from a single
 * boolean - and the middle one is somebody else enrolling a machine as yours.
 *
 * `usedFromAddress` is therefore the column that matters, and it is on the row rather than
 * behind a disclosure.
 */
export function NodeTokenHistory({
  nodeId,
  tokens,
}: {
  nodeId: string
  tokens: EnrolmentTokenView[]
}) {
  const [revoking, setRevoking] = useState<string | null>(null)

  async function revoke(token: EnrolmentTokenView) {
    const confirmed = await askConfirmation({
      title: 'Revoke this token?',
      body:
        'It can no longer enrol anything. If a machine is part-way through installing, its ' +
        'enrolment will be refused and you will need to issue another.',
      confirmLabel: 'Revoke it',
      tone: 'danger',
    })
    if (!confirmed) {
      return
    }
    setRevoking(token.id)
    router.post(
      `/admin/nodes/${nodeId}/tokens/${token.id}/revoke`,
      {},
      {preserveScroll: true, onFinish: () => setRevoking(null)},
    )
  }

  return (
    <Card
      title="Bootstrap tokens"
      description="Single use, fifteen minutes, one machine. Only a SHA-256 is stored, so the text
        of a token cannot be shown again."
      padded={tokens.length === 0}
    >
      {tokens.length === 0 ? (
        <EmptyState
          icon={<Icon name="key" />}
          title="No tokens issued"
          description="Nothing has ever been able to enrol against this record. Issue one when you
            are at the machine and ready to paste the install command."
        />
      ) : (
        <ul className="divide-y divide-ink-200 dark:divide-ink-800">
          {tokens.map((token) => (
            <li key={token.id} className="flex items-start gap-3 px-4 py-3 md:px-5">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={tokenStateTone(token.state)} dot={token.state === 'LIVE'}>
                    {tokenStateLabel(token.state)}
                  </Badge>
                  <span className="text-sm text-ink-500 dark:text-ink-400">
                    issued <RelativeTime at={token.createdAt} />
                  </span>
                </div>

                <p className="mt-1 text-sm leading-relaxed text-ink-700 dark:text-ink-300">
                  {describe(token)}
                </p>
              </div>

              {token.state === 'LIVE' ? (
                <Button
                  variant="ghost"
                  size="sm"
                  className="shrink-0 text-failed"
                  loading={revoking === token.id}
                  onClick={() => void revoke(token)}
                >
                  Revoke
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

/** One sentence per token, saying the thing an operator is actually checking. */
function describe(token: EnrolmentTokenView): string {
  switch (token.state) {
    case 'LIVE':
      return `Usable until ${localTime(token.expiresAt)}. It is the only thing that can enrol this node right now.`
    case 'USED':
      return token.usedFromAddress
        ? `Spent from ${token.usedFromAddress}. If that address is not the machine you installed on, treat this node as compromised and re-enrol it.`
        : 'Spent, from an address that was not recorded.'
    case 'REVOKED':
      return token.revokedAt
        ? `Withdrawn at ${localTime(token.revokedAt)} before anybody used it.`
        : 'Withdrawn before anybody used it.'
    default:
      return `Its fifteen minutes ran out at ${localTime(token.expiresAt)}. Issuing another is one press.`
  }
}

function localTime(at: string): string {
  const parsed = Date.parse(at)
  return Number.isFinite(parsed)
    ? new Date(parsed).toLocaleString(undefined, {dateStyle: 'medium', timeStyle: 'short'})
    : at
}
