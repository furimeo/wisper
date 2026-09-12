import {Head, usePage} from '@inertiajs/react'
import {useMemo, useState} from 'react'

import {t} from '@/i18n'
import {Badge, Card, EmptyState, Icon, Input, PageHeader} from '@/shell'

import type {AccountProfile, PlatformRole} from './authTypes'
import {AccountRow} from './AccountRow'
import {CreateAccountForm} from './CreateAccountForm'

/**
 * `GET /admin/accounts` - where accounts come from.
 *
 * Gated by `ROLE_ADMIN` in `SecurityConfig`; nothing here repeats that check, because a
 * second copy of an authorization rule is a second place for it to be wrong.
 *
 * The filter is client-side and stays that way. The whole list arrives in the props - one
 * row per person who can sign in to this installation, which is a number of accounts, not
 * a number of records - so filtering here is instant and does not need a round trip that
 * would have to be authorized all over again.
 */
type AdminAccountsProps = {
  accounts: AccountProfile[]
  /** Active operators. Suspending the last one is refused, so the button is disabled. */
  activeAdminCount: number
  roles: PlatformRole[]
}

export default function AdminAccountsPage() {
  const {accounts, activeAdminCount, roles} = usePage<AdminAccountsProps>().props
  const [query, setQuery] = useState('')

  const matching = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!needle) {
      return accounts
    }
    return accounts.filter(
      (account) =>
        account.email.toLowerCase().includes(needle) ||
        account.displayName.toLowerCase().includes(needle),
    )
  }, [accounts, query])

  const suspendedCount = accounts.filter((account) => account.status === 'SUSPENDED').length

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('auth.admin.title')} />
      <PageHeader
        title={t('auth.admin.title')}
        description={t('auth.admin.description')}
      />

      <CreateAccountForm roles={roles} />

      {/*
        The counts are a row in the body rather than an `action` in the header. Three
        badges beside the title take about 200px, and at 375px that leaves the header's
        text column narrow enough to break one word per line.
      */}
      <Card title={t('auth.admin.cardTitle')} action={<Badge>{accounts.length}</Badge>}>
        <div className="mb-3 flex flex-wrap gap-1.5">
          <Badge tone={activeAdminCount <= 1 ? 'degraded' : 'neutral'} dot>
            {t('auth.admin.operatorsBadge', {count: activeAdminCount})}
          </Badge>
          {suspendedCount > 0 ? (
            <Badge tone="failed" dot>
              {t('auth.admin.suspendedBadge', {count: suspendedCount})}
            </Badge>
          ) : null}
        </div>

        {activeAdminCount <= 1 ? (
          <p className="mb-3 rounded-lg border border-degraded/40 bg-degraded/10 px-3 py-2 text-sm leading-relaxed text-ink-800 dark:text-ink-100">
            {t('auth.admin.singleOperatorWarning')}
          </p>
        ) : null}

        <Input
          type="search"
          inputMode="search"
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          enterKeyHint="search"
          placeholder={t('auth.admin.filterPlaceholder')}
          aria-label={t('auth.admin.filterAria')}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />

        {matching.length === 0 ? (
          <EmptyState
            icon={<Icon name="account" />}
            title={t('auth.admin.emptyMatchTitle')}
            description={t('auth.admin.emptyMatchDesc', {query, total: accounts.length})}
          />
        ) : (
          <ul className="mt-3 flex flex-col gap-3">
            {matching.map((account) => (
              <AccountRow
                key={account.id}
                account={account}
                lastOperator={
                  account.platformRole === 'ADMIN' &&
                  account.status === 'ACTIVE' &&
                  activeAdminCount <= 1
                }
              />
            ))}
          </ul>
        )}
      </Card>
    </div>
  )
}
