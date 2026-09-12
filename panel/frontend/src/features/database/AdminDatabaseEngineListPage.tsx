import {Head, Link, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
import {
  Badge,
  Button,
  ByteSize,
  Card,
  DataList,
  EmptyState,
  Icon,
  PageHeader,
  RelativeTime,
} from '@/shell'

import {CreateEngineForm} from './CreateEngineForm'
import {EngineStateBadge} from './DatabaseStateBadge'
import type {DatabaseEngineView, EngineKind, ManagedDatabaseView} from './databaseTypes'
import {engineSentence, modeLabel} from './databaseVocabulary'
import {useLiveDatabases} from './useLiveDatabases'

/**
 * `GET /admin/databases` - every engine container on every node.
 *
 * The over-quota list comes first and it is not a courtesy. A node whose disk is filling is
 * usually a handful of databases doing it, and this is the screen where an operator finds
 * out which ones before the filesystem answers for them - especially on a node where the
 * quota is advisory because the volume filesystem is not XFS.
 */
type AdminEngineListProps = {
  engines: DatabaseEngineView[]
  /** Every database past its limit, worst first. */
  overQuota: ManagedDatabaseView[]
  kinds: EngineKind[]
}

export default function AdminDatabaseEngineListPage() {
  const {engines, overQuota, kinds} = usePage<AdminEngineListProps>().props
  const [adding, setAdding] = useState(false)

  useLiveDatabases(
    ['engines', 'overQuota'],
    engines.some((engine) => !engine.converged),
  )

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('database.admin.title')} />

      <PageHeader
        title={t('database.admin.title')}
        description={t('database.admin.description')}
        actions={
          <Button block className="sm:w-auto" onClick={() => setAdding(true)}>
            {t('database.admin.addEngine')}
          </Button>
        }
      />

      {overQuota.length > 0 ? (
        <Card
          title={t('database.admin.overQuotaTitle')}
          description={t('database.admin.overQuotaDesc')}
          padded={false}
        >
          <ul className="divide-y divide-ink-200 dark:divide-ink-800">
            {overQuota.map((database) => (
              <li key={database.id}>
                <Link
                  href={`/databases/${database.id}`}
                  className="flex touch-target items-center gap-3 px-4 py-3 md:px-5"
                >
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-mono text-sm">{database.name}</span>
                    <span className="block truncate text-xs text-ink-500 dark:text-ink-400">
                      {database.organizationName} · {database.nodeName}
                    </span>
                  </span>
                  <span className="shrink-0 text-sm tabular-nums text-failed">
                    <ByteSize bytes={database.usedBytes} /> of{' '}
                    <ByteSize bytes={database.quotaBytes} />
                  </span>
                  <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" />
                </Link>
              </li>
            ))}
          </ul>
        </Card>
      ) : null}

      <Card padded={false}>
        <DataList
          items={engines}
          keyOf={(engine) => engine.id}
          label={t('database.admin.enginesLabel')}
          href={(engine) => `/admin/databases/${engine.id}`}
          empty={
            <EmptyState
              icon={<Icon name="database" />}
              title={t('database.admin.empty.title')}
              description={t('database.admin.empty.description')}
              action={<Button onClick={() => setAdding(true)}>{t('database.admin.addEngine')}</Button>}
            />
          }
          primary={(engine) => (
            <span className="flex items-center gap-2">
              <span className="truncate">{engine.engineLabel}</span>
              <span className="truncate text-ink-500 dark:text-ink-400">
                {t('database.admin.onNode', {node: engine.nodeName})}
              </span>
            </span>
          )}
          secondary={(engine) => <span className="line-clamp-2">{engineSentence(engine)}</span>}
          trailing={(engine) => (
            <div className="flex flex-col items-end gap-1">
              <EngineStateBadge engine={engine} />
              <span className="text-xs tabular-nums text-ink-500 dark:text-ink-400">
                {engine.databaseCount} db
              </span>
            </div>
          )}
          columns={[
            {
              key: 'engine',
              header: t('database.admin.col.engine'),
              cell: (engine) => (
                <div className="flex flex-col gap-0.5">
                  <span>
                    {engine.engineLabel}
                    {engine.engineVersion ? ` ${engine.engineVersion}` : ''}
                  </span>
                  <span className="font-mono text-xs text-ink-500 dark:text-ink-400">
                    {engine.host}:{engine.port}
                  </span>
                </div>
              ),
            },
            {key: 'node', header: t('database.admin.col.node'), cell: (engine) => engine.nodeName},
            {
              key: 'mode',
              header: t('database.admin.col.mode'),
              cell: (engine) => (
                <span>
                  <Badge>{modeLabel(engine.mode)}</Badge>
                  {engine.owner ? (
                    <span className="ml-1.5 text-xs text-ink-500 dark:text-ink-400">
                      {engine.owner}
                    </span>
                  ) : null}
                </span>
              ),
            },
            {
              key: 'databases',
              header: t('database.admin.col.databases'),
              align: 'right',
              cell: (engine) => <span className="tabular-nums">{engine.databaseCount}</span>,
            },
            {
              key: 'disk',
              header: t('database.admin.col.disk'),
              align: 'right',
              cell: (engine) => <ByteSize bytes={engine.diskBytesUsed} fallback={t('database.list.notMeasured')} />,
            },
            {
              key: 'reported',
              header: t('database.admin.col.reported'),
              align: 'right',
              cell: (engine) => (
                <RelativeTime at={engine.reportedAt} fallback={t('database.detail.fact.never')} className="text-xs" />
              ),
            },
            {
              key: 'state',
              header: t('database.admin.col.state'),
              align: 'right',
              cell: (engine) => <EngineStateBadge engine={engine} />,
            },
          ]}
        />
      </Card>

      <CreateEngineForm open={adding} onClose={() => setAdding(false)} kinds={kinds} />
    </div>
  )
}
