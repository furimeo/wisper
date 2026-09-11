import {Head, Link, router, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {
  Badge,
  Button,
  ByteSize,
  Card,
  CardFact,
  CardFacts,
  PageHeader,
  RelativeTime,
  askConfirmation,
} from '@/shell'

import {EngineStateBadge} from './DatabaseStateBadge'
import type {DatabaseEngineView} from './databaseTypes'
import {engineSentence, modeLabel} from './databaseVocabulary'
import {useLiveDatabases} from './useLiveDatabases'

/**
 * `GET /admin/databases/{engineId}` - one engine container.
 *
 * Start and stop are desired state, not commands: they change what the node is told to
 * hold and the node converges on its own schedule. The page says so rather than pretending
 * the button did something immediate, because a page that claims a container is running
 * before the node has said so is a page that will be wrong for fifteen seconds at a time.
 *
 * Deleting takes the container out of the spec and leaves the data directory on the node
 * alone. It is refused while the engine still holds customer databases -
 * `managed_database.database_engine_id` is `ON DELETE RESTRICT` - and the page says that
 * instead of offering a button that fails.
 */
type AdminEngineDetailProps = {
  engine: DatabaseEngineView
}

export default function AdminDatabaseEngineDetailPage() {
  const {engine} = usePage<AdminEngineDetailProps>().props
  const [pending, setPending] = useState<string | null>(null)

  useLiveDatabases(['engine'], !engine.converged)

  function post(action: string, data: Record<string, string> = {}) {
    setPending(action)
    router.post(`/admin/databases/${engine.id}/${action}`, data, {
      preserveScroll: true,
      onFinish: () => setPending(null),
    })
  }

  async function stop() {
    const confirmed = await askConfirmation({
      title: `Stop ${engine.engineLabel} on ${engine.nodeName}?`,
      body:
        `Every one of the ${engine.databaseCount} databases in this container stops accepting ` +
        'connections, and every application using them starts failing. The data is untouched and ' +
        'starting it again brings them back.',
      confirmLabel: 'Stop it',
      tone: 'danger',
    })
    if (confirmed) {
      post('stop')
    }
  }

  async function remove() {
    const confirmed = await askConfirmation({
      title: 'Remove this engine from the node’s spec?',
      body:
        'The container goes away on the next reconcile. Its data directory on the node is left ' +
        'exactly where it is, so nothing is lost - but nothing will be serving it either.',
      confirmLabel: 'Remove it',
      tone: 'danger',
      requireText: engine.host,
      requireTextLabel: `Type ${engine.host} to confirm`,
    })
    if (confirmed) {
      post('delete', {confirmation: engine.host})
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Head title={`${engine.engineLabel} on ${engine.nodeName}`} />

      <PageHeader
        title={`${engine.engineLabel} on ${engine.nodeName}`}
        description={engineSentence(engine)}
      />

      <div className="flex flex-wrap items-center gap-2">
        <EngineStateBadge engine={engine} />
        <Badge>{modeLabel(engine.mode)}</Badge>
        {engine.owner ? <Badge tone="accent">{engine.owner}</Badge> : null}
      </div>

      {engine.lastError ? (
        <p className="rounded-xl border border-failed/50 bg-failed/10 px-4 py-3 text-sm leading-relaxed">
          <span className="font-medium">The node last reported: </span>
          {engine.lastError}
        </p>
      ) : null}

      <Card
        title="Desired state"
        description="The panel publishes what it wants; the node converges on it every fifteen
          seconds. Nothing here reaches into the machine."
      >
        <div className="flex flex-col gap-2 sm:flex-row">
          {engine.desiredState === 'RUNNING' ? (
            <Button
              variant="secondary"
              block
              className="sm:w-auto"
              loading={pending === 'stop'}
              onClick={() => void stop()}
            >
              Ask the node to stop it
            </Button>
          ) : (
            <Button
              block
              className="sm:w-auto"
              loading={pending === 'start'}
              onClick={() => post('start')}
            >
              Ask the node to run it
            </Button>
          )}

          <Button
            variant="danger"
            block
            className="sm:ml-auto sm:w-auto"
            disabled={!engine.removable}
            loading={pending === 'delete'}
            onClick={() => void remove()}
          >
            Remove from the spec
          </Button>
        </div>

        {engine.removable ? null : (
          <p className="mt-3 text-sm leading-relaxed text-ink-500 dark:text-ink-400">
            It still holds {engine.databaseCount} customer{' '}
            {engine.databaseCount === 1 ? 'database' : 'databases'}, so it cannot be removed.
            Drop or move those first.
          </p>
        )}
      </Card>

      <Card title="Facts">
        <CardFacts>
          <CardFact label="Address">
            <span className="font-mono text-xs">
              {engine.host}:{engine.port}
            </span>
          </CardFact>
          <CardFact label="Image">
            <span className="font-mono text-xs break-all">{engine.image}</span>
          </CardFact>
          <CardFact label="Version">{engine.engineVersion ?? 'not reported'}</CardFact>
          <CardFact label="Data directory">
            <span className="font-mono text-xs break-all">{engine.dataPath}</span>
          </CardFact>
          <CardFact label="Node">
            <Link
              href={`/admin/nodes/${engine.nodeId}`}
              className="text-accent-600 hover:underline dark:text-accent-400"
            >
              {engine.nodeName}
            </Link>
            {engine.nodeReachable ? null : (
              <span className="ml-1.5 text-ink-500 dark:text-ink-400">(out of touch)</span>
            )}
          </CardFact>
          <CardFact label="Customer databases">
            <span className="tabular-nums">{engine.databaseCount}</span>
          </CardFact>
          <CardFact label="Disk used">
            <ByteSize bytes={engine.diskBytesUsed} fallback="not measured" />
          </CardFact>
          <CardFact label="Node last reported">
            <RelativeTime at={engine.reportedAt} fallback="never" />
          </CardFact>
          <CardFact label="Created">
            <RelativeTime at={engine.createdAt} />
          </CardFact>
        </CardFacts>
      </Card>
    </div>
  )
}
