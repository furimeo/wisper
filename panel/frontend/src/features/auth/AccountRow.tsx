import {
  Badge,
  Button,
  CardFact,
  CardFacts,
  RelativeTime,
  askConfirmation,
  useFormFields,
} from '@/shell'

import type {AccountProfile} from './authTypes'

/**
 * One account in the operator's list, with the two buttons that change it.
 *
 * Suspending asks first, and says whose sessions are about to end. It is the only action
 * on this screen that takes something away from a person who is not in the room.
 *
 * `SuspendAccount` refuses when it would leave the platform with no operator who can sign
 * in. The button is disabled in that case rather than left to fail, and the reason is
 * written next to it - a control that answers "no" when pressed teaches nothing, and this
 * particular no is one an operator needs to understand before they go looking for another
 * way to do it.
 *
 * `lockedUntil` is shown when it is set. It is the sign-in throttle rather than a decision
 * anybody made, and without it "this person says they cannot get in and the account looks
 * fine" has no explanation.
 */
type AccountRowProps = {
  account: AccountProfile
  /** True when suspending this one would remove the last operator who can sign in. */
  lastOperator: boolean
}

export function AccountRow({account, lastOperator}: AccountRowProps) {
  const form = useFormFields({})
  const suspended = account.status === 'SUSPENDED'

  async function change() {
    if (suspended) {
      form.submit(`/admin/accounts/${account.id}/reactivate`)
      return
    }
    const confirmed = await askConfirmation({
      title: `Suspend ${account.email}?`,
      body: 'They cannot sign in, and every session they have is ended on its next request. Their projects keep running.',
      confirmLabel: 'Suspend',
      tone: 'danger',
    })
    if (confirmed) {
      form.submit(`/admin/accounts/${account.id}/suspend`)
    }
  }

  return (
    <li className="rounded-lg border border-ink-200 p-3 dark:border-ink-800">
      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-auto text-sm font-medium break-all">{account.displayName}</span>
        {account.platformRole === 'ADMIN' ? <Badge tone="accent">Operator</Badge> : null}
        {suspended ? (
          <Badge tone="failed" dot>
            Suspended
          </Badge>
        ) : (
          <Badge tone="running" dot>
            Active
          </Badge>
        )}
      </div>

      <p className="mt-1 font-mono text-xs break-all text-ink-600 dark:text-ink-400">
        {account.email}
      </p>

      <CardFacts>
        <CardFact label="Two-factor">{account.twoFactorEnabled ? 'On' : 'Off'}</CardFact>
        <CardFact label="Last signed in">
          <RelativeTime at={account.lastLoginAt} fallback="Never" />
          {account.lastLoginAddress ? (
            <span className="text-ink-500"> from {account.lastLoginAddress}</span>
          ) : null}
        </CardFact>
        <CardFact label="Password changed">
          <RelativeTime
            at={account.passwordChangedAt}
            fallback="Never - still the issued one"
          />
        </CardFact>
        <CardFact label="Created">
          <RelativeTime at={account.createdAt} />
        </CardFact>
        {account.lockedUntil ? (
          <CardFact label="Locked until">
            <span className="text-failed">
              <RelativeTime at={account.lockedUntil} /> - too many wrong passwords
            </span>
          </CardFact>
        ) : null}
      </CardFacts>

      <Button
        variant={suspended ? 'secondary' : 'danger'}
        className="mt-3 w-full sm:w-auto"
        loading={form.processing}
        disabled={!suspended && lastOperator}
        onClick={() => void change()}
      >
        {suspended ? 'Let them sign in again' : 'Suspend and sign out'}
      </Button>

      {!suspended && lastOperator ? (
        <p className="mt-2 text-xs leading-relaxed text-ink-500">
          The last operator who can still sign in. Promote somebody else first, or nobody
          will be able to administer the platform.
        </p>
      ) : null}
    </li>
  )
}
