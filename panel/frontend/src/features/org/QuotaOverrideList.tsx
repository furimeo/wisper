import {router} from '@inertiajs/react'
import {useState} from 'react'

import {Badge, Button, Card, EmptyState, Icon, RelativeTime, askConfirmation} from '@/shell'

import {GrantOverrideDialog} from './GrantOverrideDialog'
import type {QuotaAllowance, QuotaOverride, QuotaResource} from './orgTypes'
import {quotaFigure, quotaLabel} from './quotaVocabulary'

/**
 * The exceptions one tenant has to its plan, and the two buttons that change them.
 *
 * An expired exception is still a row in the table - `QuotaGuard` stops honouring it, but
 * nothing deletes it - so this marks it as expired rather than dropping it. An operator
 * hunting for "why is this customer suddenly refused" needs to see the exception that ran
 * out, and a list that hides it answers the wrong question.
 *
 * Revoking is confirmed, because it takes effect on the next request the customer makes:
 * an exception raising a database limit from 2 to 5 does not delete the three extra
 * databases, but it does mean the next thing they try is refused.
 */
export function QuotaOverrideList({
  organizationId,
  overrides,
  resources,
  allowances,
}: {
  organizationId: string
  overrides: QuotaOverride[]
  resources: QuotaResource[]
  allowances: QuotaAllowance[]
}) {
  const [granting, setGranting] = useState(false)
  const [working, setWorking] = useState<string | null>(null)
  const now = Date.now()

  async function revoke(override: QuotaOverride) {
    const confirmed = await askConfirmation({
      title: `Revoke the ${quotaLabel(override.resource).toLowerCase()} exception?`,
      body:
        'This organization goes back to whatever its plan allows on the next request it makes. ' +
        'Nothing it has already created is removed.',
      confirmLabel: 'Revoke',
      tone: 'danger',
    })
    if (confirmed) {
      setWorking(override.resource)
      router.post(
        `/admin/organizations/${organizationId}/quota-overrides/${override.resource}/revoke`,
        {},
        {preserveScroll: true, onFinish: () => setWorking(null)},
      )
    }
  }

  return (
    <Card
      title="Exceptions"
      description="Limits that apply to this organization instead of its plan's."
      action={
        <Button size="sm" onClick={() => setGranting(true)}>
          Grant
        </Button>
      }
      padded={false}
    >
      {overrides.length === 0 ? (
        <EmptyState
          icon={<Icon name="plan" />}
          title="No exceptions"
          description="Every limit comes from the plan. Grant one to raise - or lower - a single
            resource for this tenant without moving them onto a different tier."
          action={<Button onClick={() => setGranting(true)}>Grant an exception</Button>}
        />
      ) : (
        <ul className="divide-y divide-ink-200 dark:divide-ink-800">
          {overrides.map((override) => {
            const expired =
              override.expiresAt !== null && Date.parse(override.expiresAt) <= now
            return (
              <li
                key={override.id}
                className="flex flex-col gap-2 px-4 py-3 md:flex-row md:items-start md:justify-between md:px-5"
              >
                <div className="min-w-0">
                  <p className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-medium text-ink-900 dark:text-ink-100">
                      {quotaLabel(override.resource)}
                    </span>
                    <Badge tone={expired ? 'neutral' : 'accent'}>
                      {quotaFigure(override.resource, override.limitValue)}
                    </Badge>
                    {expired ? <Badge tone="degraded">Expired</Badge> : null}
                  </p>

                  <p className="mt-1 text-sm leading-relaxed text-ink-700 dark:text-ink-300">
                    {override.reason}
                  </p>

                  <p className="mt-1 text-xs text-ink-500 dark:text-ink-400">
                    Granted <RelativeTime at={override.createdAt} />
                    {override.expiresAt ? (
                      <>
                        {expired ? ', expired ' : ', expires '}
                        <RelativeTime at={override.expiresAt} />
                      </>
                    ) : (
                      ', no expiry'
                    )}
                  </p>
                </div>

                <Button
                  variant="secondary"
                  size="sm"
                  className="md:shrink-0"
                  loading={working === override.resource}
                  onClick={() => void revoke(override)}
                >
                  Revoke
                </Button>
              </li>
            )
          })}
        </ul>
      )}

      <GrantOverrideDialog
        open={granting}
        onClose={() => setGranting(false)}
        organizationId={organizationId}
        resources={resources}
        allowances={allowances}
      />
    </Card>
  )
}
