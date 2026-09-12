import {t} from '@/i18n'
import {Badge, Card, EmptyState, Icon} from '@/shell'

import type {AccountOrganization, ApiTokenView} from './authTypes'
import {ApiTokenRow} from './ApiTokenRow'

/**
 * Every token this account has issued, live ones first.
 *
 * The order comes back newest-first from `ApiTokenRepository`, and this only lifts the
 * live ones above the dead: the question somebody opens this page with is "what still
 * works", and a revoked token from last March at the top of the list answers a different
 * one. Within each half the server's order is kept.
 */
type ApiTokenListProps = {
  tokens: ApiTokenView[]
  /** Used to turn `organizationId` into a name the customer recognises. */
  organizations: AccountOrganization[]
}

export function ApiTokenList({tokens, organizations}: ApiTokenListProps) {
  const names = new Map(organizations.map((organization) => [organization.id, organization.name]))
  const live = tokens.filter((token) => token.live)
  const dead = tokens.filter((token) => !token.live)
  const ordered = [...live, ...dead]

  return (
    <Card
      title={t('auth.tokens.cardTitle')}
      description={t('auth.tokens.cardDesc')}
      action={<Badge tone={live.length > 0 ? 'running' : 'neutral'}>{t('auth.tokens.activeBadge', {count: live.length})}</Badge>}
      padded={ordered.length > 0}
    >
      {ordered.length === 0 ? (
        <EmptyState
          icon={<Icon name="key" />}
          title={t('auth.tokens.emptyTitle')}
          description={t('auth.tokens.emptyDesc')}
        />
      ) : (
        <ul className="flex flex-col gap-3">
          {ordered.map((token) => (
            <ApiTokenRow
              key={token.id}
              token={token}
              organizationName={
                token.organizationId
                  ? (names.get(token.organizationId) ?? t('auth.tokens.anotherOrg'))
                  : null
              }
            />
          ))}
        </ul>
      )}
    </Card>
  )
}
