import {Head, Link, usePage} from '@inertiajs/react'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'
import {t} from '@/i18n'
import {Card, CardFact, CardFacts, PageHeader, RelativeTime} from '@/shell'
import type {MemberRole} from '@/shell'

import {ConnectionDetails} from './ConnectionDetails'
import {DatabaseActions} from './DatabaseActions'
import {DatabaseQuotaForm} from './DatabaseQuotaForm'
import {DatabaseStateBadge} from './DatabaseStateBadge'
import type {ConnectionString, ManagedDatabaseView} from './databaseTypes'
import {databaseAddress, databaseSentence} from './databaseVocabulary'
import {useLiveDatabases} from './useLiveDatabases'

/**
 * `GET /databases/{databaseId}` - one database.
 *
 * `connection` is the one-shot flash prop from a reveal or a rotation. When it is here it
 * goes above everything, because copying it is the only thing that matters on that render
 * and it cannot be done later.
 */
type DatabaseDetailProps = {
  database: ManagedDatabaseView
  viewerRole: MemberRole
  /** MANAGED_DATABASE, so the page can say how many more the plan permits. */
  databaseAllowance: QuotaAllowance
  connection?: ConnectionString
}

export default function DatabaseDetailPage() {
  const {database, viewerRole, databaseAllowance, connection} =
    usePage<DatabaseDetailProps>().props

  useLiveDatabases(['database'], database.inFlight)

  return (
    <div className="flex flex-col gap-4">
      <Head title={database.name} />

      <PageHeader title={database.name} description={databaseSentence(database)} />

      <div className="flex flex-wrap items-center gap-2">
        <DatabaseStateBadge database={database} />
        <span className="text-sm text-ink-500 dark:text-ink-400">
          {database.engineLabel}
          {database.engineVersion ? ` ${database.engineVersion}` : ''} ·{' '}
          {database.dedicated ? t('database.detail.dedicatedInstance') : t('database.detail.sharedInstance')}
        </span>
      </div>

      {connection ? <ConnectionDetails connection={connection} /> : null}

      {database.lastError && database.state === 'FAILED' ? (
        <p className="rounded-xl border border-failed/50 bg-failed/10 px-4 py-3 text-sm leading-relaxed">
          <span className="font-medium">{t('database.detail.nodeRefused')}</span>
          {database.lastError}
        </p>
      ) : null}

      <DatabaseActions database={database} viewerRole={viewerRole} />

      <DatabaseQuotaForm database={database} viewerRole={viewerRole} />

      <Card title={t('database.detail.where.title')}>
        <CardFacts>
          <CardFact label={t('database.detail.fact.address')}>
            <span className="font-mono text-xs break-all">{databaseAddress(database)}</span>
          </CardFact>
          <CardFact label={t('database.detail.fact.user')}>
            <span className="font-mono text-xs">{database.username}</span>
          </CardFact>
          <CardFact label={t('database.detail.fact.project')}>
            <Link
              href={`/projects/${database.projectId}`}
              className="text-accent-600 hover:underline dark:text-accent-400"
            >
              {database.projectName}
            </Link>
          </CardFact>
          <CardFact label={t('database.detail.fact.organization')}>{database.organizationName}</CardFact>
          <CardFact label={t('database.detail.fact.node')}>
            {database.nodeName}
            {database.nodeReachable ? null : (
              <span className="ml-1.5 text-ink-500 dark:text-ink-400">{t('database.detail.fact.outOfTouch')}</span>
            )}
          </CardFact>
          <CardFact label={t('database.detail.fact.created')}>
            <RelativeTime at={database.provisionedAt} fallback={t('database.detail.fact.notYet')} />
          </CardFact>
          <CardFact label={t('database.detail.fact.passwordChanged')}>
            <RelativeTime at={database.passwordRotatedAt} fallback={t('database.detail.fact.never')} />
          </CardFact>
          <CardFact label={t('database.detail.fact.sizeMeasured')}>
            <RelativeTime at={database.measuredAt} fallback={t('database.detail.fact.never')} />
          </CardFact>
        </CardFacts>
      </Card>

      <Card
        title={t('database.detail.plan.title')}
        description={t('database.detail.plan.description')}
      >
        <QuotaMeter allowance={databaseAllowance} />
      </Card>
    </div>
  )
}
