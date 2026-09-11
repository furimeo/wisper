import {Head, Link, usePage} from '@inertiajs/react'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'
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
          {database.dedicated ? 'dedicated instance' : 'shared instance'}
        </span>
      </div>

      {connection ? <ConnectionDetails connection={connection} /> : null}

      {database.lastError && database.state === 'FAILED' ? (
        <p className="rounded-xl border border-failed/50 bg-failed/10 px-4 py-3 text-sm leading-relaxed">
          <span className="font-medium">The node refused this database: </span>
          {database.lastError}
        </p>
      ) : null}

      <DatabaseActions database={database} viewerRole={viewerRole} />

      <DatabaseQuotaForm database={database} viewerRole={viewerRole} />

      <Card title="Where it lives">
        <CardFacts>
          <CardFact label="Address">
            <span className="font-mono text-xs break-all">{databaseAddress(database)}</span>
          </CardFact>
          <CardFact label="User">
            <span className="font-mono text-xs">{database.username}</span>
          </CardFact>
          <CardFact label="Project">
            <Link
              href={`/projects/${database.projectId}`}
              className="text-accent-600 hover:underline dark:text-accent-400"
            >
              {database.projectName}
            </Link>
          </CardFact>
          <CardFact label="Organization">{database.organizationName}</CardFact>
          <CardFact label="Node">
            {database.nodeName}
            {database.nodeReachable ? null : (
              <span className="ml-1.5 text-ink-500 dark:text-ink-400">(out of touch)</span>
            )}
          </CardFact>
          <CardFact label="Created">
            <RelativeTime at={database.provisionedAt} fallback="not yet" />
          </CardFact>
          <CardFact label="Password last changed">
            <RelativeTime at={database.passwordRotatedAt} fallback="never" />
          </CardFact>
          <CardFact label="Size last measured">
            <RelativeTime at={database.measuredAt} fallback="never" />
          </CardFact>
        </CardFacts>
      </Card>

      <Card
        title="Your plan"
        description="How many databases this organization may have, across every project."
      >
        <QuotaMeter allowance={databaseAllowance} />
      </Card>
    </div>
  )
}
