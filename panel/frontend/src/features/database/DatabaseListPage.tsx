import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
import {
  Badge,
  Button,
  ButtonLink,
  ByteSize,
  Card,
  DataList,
  EmptyState,
  Icon,
  PageHeader,
} from '@/shell'

import {CreateDatabaseForm, projectChoices} from './CreateDatabaseForm'
import {DatabaseStateBadge} from './DatabaseStateBadge'
import type {EngineKind, ManagedDatabaseView} from './databaseTypes'
import {databaseAddress, databaseSentence} from './databaseVocabulary'
import {useLiveDatabases} from './useLiveDatabases'

/**
 * `GET /databases` - every database this person can reach, across every organization.
 *
 * Flat rather than nested under a project, and deliberately: the thing a customer has in
 * hand when they open this is the database, not the path to it. They are usually here for
 * one of two reasons - to read a connection string on their phone, or because something
 * cannot connect - so the row leads with the address and the state and nothing else.
 *
 * No connection string is on this page. `ManagedDatabaseView` has no password field at
 * all; revealing one is a POST that is written to the audit trail first.
 */
type DatabaseListProps = {
  databases: ManagedDatabaseView[]
  engines: EngineKind[]
}

export default function DatabaseListPage() {
  const {databases, engines} = usePage<DatabaseListProps>().props
  const [creating, setCreating] = useState(false)

  const projects = projectChoices(databases)
  useLiveDatabases(
    ['databases'],
    databases.some((database) => database.inFlight),
  )

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('database.list.title')} />

      <PageHeader
        title={t('database.list.title')}
        description={t('database.list.description')}
        actions={
          projects.length === 0 ? null : (
            <Button block className="sm:w-auto" onClick={() => setCreating(true)}>
              {t('database.list.action.new')}
            </Button>
          )
        }
      />

      <Card padded={false}>
        <DataList
          items={databases}
          keyOf={(database) => database.id}
          label={t('database.list.label')}
          href={(database) => `/databases/${database.id}`}
          empty={
            projects.length === 0 ? (
              <EmptyState
                icon={<Icon name="database" />}
                title={t('database.list.emptyNoProjects.title')}
                description={t('database.list.emptyNoProjects.description')}
                action={<ButtonLink href="/">{t('database.list.emptyNoProjects.action')}</ButtonLink>}
              />
            ) : (
              <EmptyState
                icon={<Icon name="database" />}
                title={t('database.list.empty.title')}
                description={t('database.list.empty.description')}
                action={<Button onClick={() => setCreating(true)}>{t('database.list.action.new')}</Button>}
              />
            )
          }
          primary={(database) => (
            <span className="flex items-center gap-2">
              <span className="truncate font-mono">{database.name}</span>
              <Badge>{database.engineLabel}</Badge>
            </span>
          )}
          secondary={(database) => (
            <span className="line-clamp-2">
              {database.projectName} · {databaseSentence(database)}
            </span>
          )}
          trailing={(database) => (
            <div className="flex flex-col items-end gap-1">
              <DatabaseStateBadge database={database} />
              <span className="text-xs tabular-nums text-ink-500 dark:text-ink-400">
                {database.usedBytes === null ? (
                  t('database.list.notMeasured')
                ) : (
                  <>
                    <ByteSize bytes={database.usedBytes} /> of{' '}
                    <ByteSize bytes={database.quotaBytes} />
                  </>
                )}
              </span>
            </div>
          )}
          columns={[
            {
              key: 'name',
              header: t('database.list.col.database'),
              cell: (database) => (
                <div className="flex flex-col gap-0.5">
                  <span className="font-mono">{database.name}</span>
                  <span className="text-xs text-ink-500 dark:text-ink-400">
                    {database.organizationName} · {database.projectName}
                  </span>
                </div>
              ),
            },
            {
              key: 'engine',
              header: t('database.list.col.engine'),
              cell: (database) => (
                <span>
                  {database.engineLabel}
                  {database.engineVersion ? (
                    <span className="text-ink-500 dark:text-ink-400"> {database.engineVersion}</span>
                  ) : null}
                  {database.dedicated ? <Badge className="ml-1.5">{t('database.list.dedicatedBadge')}</Badge> : null}
                </span>
              ),
            },
            {
              key: 'address',
              header: t('database.list.col.address'),
              cell: (database) => (
                <span className="font-mono text-xs">{databaseAddress(database)}</span>
              ),
            },
            {
              key: 'size',
              header: t('database.list.col.size'),
              align: 'right',
              cell: (database) =>
                database.usedBytes === null ? (
                  <span className="text-ink-400">{t('database.list.notMeasured')}</span>
                ) : (
                  <span className={database.overQuota ? 'text-failed tabular-nums' : 'tabular-nums'}>
                    <ByteSize bytes={database.usedBytes} /> / <ByteSize bytes={database.quotaBytes} />
                  </span>
                ),
            },
            {
              key: 'state',
              header: t('database.list.col.state'),
              align: 'right',
              cell: (database) => <DatabaseStateBadge database={database} />,
            },
          ]}
        />
      </Card>

      <CreateDatabaseForm
        open={creating}
        onClose={() => setCreating(false)}
        engines={engines}
        projects={projects}
      />
    </div>
  )
}
