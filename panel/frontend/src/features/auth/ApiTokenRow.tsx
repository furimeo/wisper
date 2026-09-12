import {useState} from 'react'

import {t} from '@/i18n'
import {Badge, Button, CardFact, CardFacts, Input, RelativeTime, useFormFields} from '@/shell'

import type {ApiTokenView} from './authTypes'

/**
 * One token in the list, with the two-step revoke.
 *
 * Revoking is irreversible and the button sits next to a name that is easy to misread on
 * a phone, so pressing it opens an inline confirmation rather than acting. Inline rather
 * than the shell's confirmation dialog because there is something to type - the reason,
 * which is kept with the record - and a dialog with a text field on top of the row it is
 * asking about is harder to read than the row itself.
 *
 * A revoked token stays in the list rather than disappearing. `revokedAt` and
 * `revokedReason` are what somebody reads when a build starts failing at three in the
 * morning, and a row that vanished would leave them wondering whether it ever existed.
 */
type ApiTokenRowProps = {
  token: ApiTokenView
  /** Resolved from the account's memberships; null for a platform-wide token. */
  organizationName: string | null
}

export function ApiTokenRow({token, organizationName}: ApiTokenRowProps) {
  const [confirming, setConfirming] = useState(false)
  const form = useFormFields({reason: ''})

  return (
    <li className="rounded-lg border border-ink-200 p-3 dark:border-ink-800">
      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-auto text-sm font-medium break-all">{token.name}</span>
        {token.revokedAt ? (
          <Badge tone="failed" dot>
            {t('auth.tokens.badgeRevoked')}
          </Badge>
        ) : token.live ? (
          <Badge tone="running" dot>
            {t('auth.tokens.badgeActive')}
          </Badge>
        ) : (
          <Badge tone="degraded" dot>
            {t('auth.tokens.badgeExpired')}
          </Badge>
        )}
      </div>

      <p className="mt-1.5 font-mono text-xs break-all text-ink-500">{token.maskedValue}</p>

      <ul className="mt-2 flex flex-wrap gap-1">
        {token.scopes.map((scope) => (
          <li
            key={scope}
            className="rounded-full bg-ink-100 px-2 py-0.5 font-mono text-[0.6875rem] text-ink-600 dark:bg-ink-800 dark:text-ink-400"
          >
            {scope}
          </li>
        ))}
      </ul>

      <CardFacts>
        <CardFact label={t('auth.tokens.factOrg')}>{organizationName ?? t('auth.tokens.factPlatformWide')}</CardFact>
        <CardFact label={t('auth.tokens.factCreated')}>
          <RelativeTime at={token.createdAt} />
        </CardFact>
        <CardFact label={t('auth.tokens.factExpires')}>
          <RelativeTime at={token.expiresAt} fallback={t('auth.tokens.factNever')} />
        </CardFact>
        <CardFact label={t('auth.tokens.factLastUsed')}>
          <RelativeTime at={token.lastUsedAt} fallback={t('auth.tokens.factNeverUsed')} />
          {token.lastUsedAddress ? (
            <span className="text-ink-500">{t('auth.tokens.fromIp', {ip: token.lastUsedAddress})}</span>
          ) : null}
        </CardFact>
        {token.revokedAt ? (
          <CardFact label={t('auth.tokens.factRevoked')}>
            <RelativeTime at={token.revokedAt} />
            {token.revokedReason ? ` - ${token.revokedReason}` : ''}
          </CardFact>
        ) : null}
      </CardFacts>

      {token.revokedAt ? null : confirming ? (
        <form
          className="mt-3 flex flex-col gap-3"
          onSubmit={(event) => {
            event.preventDefault()
            form.submit(`/settings/tokens/${token.id}/revoke`, {preserveState: 'errors'})
          }}
        >
          <Input
            {...form.bind('reason')}
            label={t('auth.tokens.whyRevoke')}
            maxLength={200}
            autoComplete="off"
            enterKeyHint="done"
            placeholder={t('auth.tokens.whyPlaceholder')}
          />
          <div className="flex flex-col gap-2 sm:flex-row">
            <Button
              type="submit"
              variant="danger"
              className="w-full sm:w-auto"
              loading={form.processing}
            >
              {form.processing ? t('auth.tokens.revoking') : t('auth.tokens.revokeNow')}
            </Button>
            <Button
              variant="ghost"
              className="w-full sm:w-auto"
              onClick={() => setConfirming(false)}
            >
              {t('auth.tokens.keepIt')}
            </Button>
          </div>
          <p className="text-xs leading-relaxed text-ink-500">
            {t('auth.tokens.revokeNotice')}
          </p>
        </form>
      ) : (
        <Button
          variant="secondary"
          className="mt-3 w-full sm:w-auto"
          onClick={() => setConfirming(true)}
        >
          {t('auth.tokens.revokeBtn')}
        </Button>
      )}
    </li>
  )
}
