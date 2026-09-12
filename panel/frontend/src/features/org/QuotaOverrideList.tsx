import {router} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
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
      title: t('org.overrides.confirmTitle', {resource: quotaLabel(override.resource).toLowerCase()}),
      body: t('org.overrides.confirmBody'),
      confirmLabel: t('org.overrides.confirmBtn'),
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
      title={t('org.overrides.title')}
      description={t('org.overrides.description')}
      action={
        <Button size="sm" onClick={() => setGranting(true)}>
          {t('org.overrides.grantBtn')}
        </Button>
      }
      padded={false}
    >
      {overrides.length === 0 ? (
        <EmptyState
          icon={<Icon name="plan" />}
          title={t('org.overrides.emptyTitle')}
          description={t('org.overrides.emptyDesc')}
          action={<Button onClick={() => setGranting(true)}>{t('org.overrides.emptyBtn')}</Button>}
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
                    {expired ? <Badge tone="degraded">{t('org.overrides.badgeExpired')}</Badge> : null}
                  </p>

                  <p className="mt-1 text-sm leading-relaxed text-ink-700 dark:text-ink-300">
                    {override.reason}
                  </p>

                  <p className="mt-1 text-xs text-ink-500 dark:text-ink-400">
                    {t('org.overrides.granted', {time: ''})}
                    <RelativeTime at={override.createdAt} />
                    {override.expiresAt ? (
                      <>
                        {expired
                          ? t('org.overrides.expired', {time: ''})
                          : t('org.overrides.expires', {time: ''})}
                        <RelativeTime at={override.expiresAt} />
                      </>
                    ) : (
                      t('org.overrides.noExpiry')
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
                  {t('org.overrides.revokeBtn')}
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
