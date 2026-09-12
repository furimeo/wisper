import {router} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
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
      title: t('node.tokens.revokeConfirm.title'),
      body: t('node.tokens.revokeConfirm.body'),
      confirmLabel: t('node.tokens.revokeConfirm.confirm'),
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
      title={t('node.tokens.title')}
      description={t('node.tokens.description')}
      padded={tokens.length === 0}
    >
      {tokens.length === 0 ? (
        <EmptyState
          icon={<Icon name="key" />}
          title={t('node.tokens.empty.title')}
          description={t('node.tokens.empty.description')}
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
                    {t('node.tokens.issuedAt')}<RelativeTime at={token.createdAt} />
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
                  {t('node.tokens.revokeButton')}
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
      return t('node.tokens.desc.live', {time: localTime(token.expiresAt)})
    case 'USED':
      return token.usedFromAddress
        ? t('node.tokens.desc.usedFrom', {address: token.usedFromAddress})
        : t('node.tokens.desc.usedUnknown')
    case 'REVOKED':
      return token.revokedAt
        ? t('node.tokens.desc.revokedAt', {time: localTime(token.revokedAt)})
        : t('node.tokens.desc.revoked')
    default:
      return t('node.tokens.desc.expired', {time: localTime(token.expiresAt)})
  }
}

function localTime(at: string): string {
  const parsed = Date.parse(at)
  return Number.isFinite(parsed)
    ? new Date(parsed).toLocaleString(undefined, {dateStyle: 'medium', timeStyle: 'short'})
    : at
}
