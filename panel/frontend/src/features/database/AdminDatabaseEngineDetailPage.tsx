import {Head, Link, router, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
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
      title: t('database.adminDetail.stopConfirm.title', {engine: engine.engineLabel, node: engine.nodeName}),
      body: t('database.adminDetail.stopConfirm.body', {count: engine.databaseCount}),
      confirmLabel: t('database.adminDetail.stopConfirm.confirm'),
      tone: 'danger',
    })
    if (confirmed) {
      post('stop')
    }
  }

  async function remove() {
    const confirmed = await askConfirmation({
      title: t('database.adminDetail.removeConfirm.title'),
      body: t('database.adminDetail.removeConfirm.body'),
      confirmLabel: t('database.adminDetail.removeConfirm.confirm'),
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
      <Head title={t('database.adminDetail.title', {engine: engine.engineLabel, node: engine.nodeName})} />

      <PageHeader
        title={t('database.adminDetail.title', {engine: engine.engineLabel, node: engine.nodeName})}
        description={engineSentence(engine)}
      />

      <div className="flex flex-wrap items-center gap-2">
        <EngineStateBadge engine={engine} />
        <Badge>{modeLabel(engine.mode)}</Badge>
        {engine.owner ? <Badge tone="accent">{engine.owner}</Badge> : null}
      </div>

      {engine.lastError ? (
        <p className="rounded-xl border border-failed/50 bg-failed/10 px-4 py-3 text-sm leading-relaxed">
          <span className="font-medium">{t('database.adminDetail.reportedNotice')}</span>
          {engine.lastError}
        </p>
      ) : null}

      <Card
        title={t('database.adminDetail.desiredState.title')}
        description={t('database.adminDetail.desiredState.description')}
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
              {t('database.adminDetail.askStop')}
            </Button>
          ) : (
            <Button
              block
              className="sm:w-auto"
              loading={pending === 'start'}
              onClick={() => post('start')}
            >
              {t('database.adminDetail.askRun')}
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
            {t('database.adminDetail.removeFromSpec')}
          </Button>
        </div>

        {engine.removable ? null : (
          <p className="mt-3 text-sm leading-relaxed text-ink-500 dark:text-ink-400">
            {t('database.adminDetail.cannotRemove', {count: engine.databaseCount})}
          </p>
        )}
      </Card>

      <Card title={t('database.adminDetail.facts.title')}>
        <CardFacts>
          <CardFact label={t('database.adminDetail.facts.address')}>
            <span className="font-mono text-xs">
              {engine.host}:{engine.port}
            </span>
          </CardFact>
          <CardFact label={t('database.adminDetail.facts.image')}>
            <span className="font-mono text-xs break-all">{engine.image}</span>
          </CardFact>
          <CardFact label={t('database.adminDetail.facts.version')}>{engine.engineVersion ?? t('database.adminDetail.facts.notReported')}</CardFact>
          <CardFact label={t('database.adminDetail.facts.dataPath')}>
            <span className="font-mono text-xs break-all">{engine.dataPath}</span>
          </CardFact>
          <CardFact label={t('database.adminDetail.facts.node')}>
            <Link
              href={`/admin/nodes/${engine.nodeId}`}
              className="text-accent-600 hover:underline dark:text-accent-400"
            >
              {engine.nodeName}
            </Link>
            {engine.nodeReachable ? null : (
              <span className="ml-1.5 text-ink-500 dark:text-ink-400">{t('database.detail.fact.outOfTouch')}</span>
            )}
          </CardFact>
          <CardFact label={t('database.adminDetail.facts.customerDatabases')}>
            <span className="tabular-nums">{engine.databaseCount}</span>
          </CardFact>
          <CardFact label={t('database.adminDetail.facts.diskUsed')}>
            <ByteSize bytes={engine.diskBytesUsed} fallback={t('database.list.notMeasured')} />
          </CardFact>
          <CardFact label={t('database.adminDetail.facts.reported')}>
            <RelativeTime at={engine.reportedAt} fallback={t('database.detail.fact.never')} />
          </CardFact>
          <CardFact label={t('database.adminDetail.facts.created')}>
            <RelativeTime at={engine.createdAt} />
          </CardFact>
        </CardFacts>
      </Card>
    </div>
  )
}
