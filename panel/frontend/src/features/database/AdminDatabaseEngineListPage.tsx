import {Head, Link, usePage} from '@inertiajs/react'
import {useState} from 'react'

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
      <Head title="Database engines" />

      <PageHeader
        title="Database engines"
        description="One shared PostgreSQL and one shared MySQL per node, with a database and a
          login inside them per customer. Hundreds of separate containers would cost 30-50MB each
          sitting idle; a tenant who needs the isolation gets a dedicated instance."
        actions={
          <Button block className="sm:w-auto" onClick={() => setAdding(true)}>
            Add an engine
          </Button>
        }
      />

      {overQuota.length > 0 ? (
        <Card
          title="Over their limit"
          description="These are what fills a node's disk. On a node without XFS project quota
            nothing stops them, so this list is the only warning there is."
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
          label="database engines"
          href={(engine) => `/admin/databases/${engine.id}`}
          empty={
            <EmptyState
              icon={<Icon name="database" />}
              title="No engine containers yet"
              description="None have been created, and that is normal on a new platform: the first
                customer who asks for a database gets one made for them on the node their services
                run on. Add one here if you would rather it were already running."
              action={<Button onClick={() => setAdding(true)}>Add an engine</Button>}
            />
          }
          primary={(engine) => (
            <span className="flex items-center gap-2">
              <span className="truncate">{engine.engineLabel}</span>
              <span className="truncate text-ink-500 dark:text-ink-400">on {engine.nodeName}</span>
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
              header: 'Engine',
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
            {key: 'node', header: 'Node', cell: (engine) => engine.nodeName},
            {
              key: 'mode',
              header: 'Mode',
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
              header: 'Databases',
              align: 'right',
              cell: (engine) => <span className="tabular-nums">{engine.databaseCount}</span>,
            },
            {
              key: 'disk',
              header: 'Disk',
              align: 'right',
              cell: (engine) => <ByteSize bytes={engine.diskBytesUsed} fallback="not measured" />,
            },
            {
              key: 'reported',
              header: 'Reported',
              align: 'right',
              cell: (engine) => (
                <RelativeTime at={engine.reportedAt} fallback="never" className="text-xs" />
              ),
            },
            {
              key: 'state',
              header: 'State',
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
