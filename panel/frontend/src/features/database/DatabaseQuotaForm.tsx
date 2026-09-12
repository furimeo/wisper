import {ByteAmountField} from '@/features/service/ByteAmountField'
import {t} from '@/i18n'
import {ByteSize, Button, Card, formatBytes, mayWrite, useFormFields} from '@/shell'
import type {MemberRole} from '@/shell'

import type {ManagedDatabaseView} from './databaseTypes'

/**
 * How large this database may get, and how large it actually is.
 *
 * The bar and the form are one card because they are one decision: a customer opens this
 * because something said "over quota", and the number they need to change is the number
 * they are looking at.
 *
 * A size that has never been measured draws no bar. `usedBytes === null` means the node
 * has not measured it yet - which it does on its own schedule - and an empty bar would say
 * "nothing stored" about a database that might be full.
 */
interface QuotaValues {
  quotaBytes: string
  [key: string]: string
}

const WARN_SHARE = 0.85

export function DatabaseQuotaForm({
  database,
  viewerRole,
}: {
  database: ManagedDatabaseView
  viewerRole: MemberRole
}) {
  const form = useFormFields<QuotaValues>({quotaBytes: String(database.quotaBytes)})
  const writable = mayWrite(viewerRole)

  const measured = database.usedBytes
  const share =
    measured === null || database.quotaBytes <= 0
      ? null
      : Math.min(1, measured / database.quotaBytes)
  const over = database.overQuota

  return (
    <Card
      title={t('database.quota.title')}
      description={t('database.quota.description')}
      footer={
        writable ? (
          <div className="flex flex-col gap-2 sm:flex-row-reverse">
            <Button
              block
              className="sm:w-auto"
              loading={form.processing}
              disabled={!form.dirty}
              onClick={() => form.submit(`/databases/${database.id}/quota`)}
            >
              {t('database.quota.save')}
            </Button>
            {form.dirty ? (
              <Button variant="ghost" block className="sm:w-auto" onClick={form.reset}>
                {t('database.quota.discard')}
              </Button>
            ) : null}
          </div>
        ) : undefined
      }
    >
      <div className="flex flex-col gap-4">
        <div>
          <div className="flex items-baseline justify-between gap-3">
            <span className="text-sm font-medium text-ink-800 dark:text-ink-200">{t('database.quota.used')}</span>
            <span
              className={
                over
                  ? 'text-sm tabular-nums text-failed'
                  : share !== null && share >= WARN_SHARE
                    ? 'text-sm tabular-nums text-degraded'
                    : 'text-sm tabular-nums text-ink-500 dark:text-ink-400'
              }
            >
              {measured === null ? (
                t('database.quota.notMeasuredYet')
              ) : (
                <>
                  <ByteSize bytes={measured} /> of <ByteSize bytes={database.quotaBytes} />
                </>
              )}
            </span>
          </div>

          <div
            className="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-ink-200 dark:bg-ink-800"
            role="img"
            aria-label={
              share === null
                ? t('database.quota.ariaNotMeasured')
                : t('database.quota.ariaUsed', {percent: Math.round(share * 100)})
            }
          >
            {share === null ? null : (
              <div
                className={
                  over ? 'h-full bg-failed' : share >= WARN_SHARE ? 'h-full bg-degraded' : 'h-full bg-accent-500'
                }
                style={{width: `${Math.round((over ? 1 : share) * 100)}%`}}
              />
            )}
          </div>

          {over ? (
            <p className="mt-2 text-sm leading-relaxed text-failed">
              {t('database.quota.pastLimit')}
            </p>
          ) : null}
        </div>

        {writable ? (
          <form
            onSubmit={(event) => {
              event.preventDefault()
              form.submit(`/databases/${database.id}/quota`)
            }}
          >
            <ByteAmountField
              label={t('database.quota.limit')}
              name="quotaBytes"
              bytes={form.data.quotaBytes}
              onBytes={(value) => form.set('quotaBytes', value)}
              error={form.error('quotaBytes')}
              hint={t('database.quota.limitHint')}
            />
            <button type="submit" className="sr-only">
              {t('database.quota.save')}
            </button>
          </form>
        ) : (
          <p className="text-sm text-ink-500 dark:text-ink-400">
            {t('database.quota.readOnly', {limit: formatBytes(database.quotaBytes)})}
          </p>
        )}
      </div>
    </Card>
  )
}
