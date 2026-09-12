import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {useI18n} from '@/i18n'
import {Button, Card, EmptyState, Icon, PageHeader} from '@/shell'

import {CreateNodeForm} from './CreateNodeForm'
import {NodeFleetList} from './NodeFleetList'
import {ClonedNodeAlert} from './NodeSuspensionAlert'
import type {NodeSummary} from './nodeTypes'
import {needsAttention} from './nodeVocabulary'
import {useLiveNodes} from './useLiveNodes'

/**
 * `GET /admin/nodes` - the fleet.
 *
 * Three bands, in the order an operator reads them: what is wrong, how much room there is,
 * and then everything.
 *
 * `attention` is the server's own query - suspended or draining - and this page widens it
 * rather than replacing it. A node whose Docker has gone, one that never finished applying
 * a generation and one running without gVisor all need dealing with too, and none of them
 * is a lifecycle the server query matches. Widening it here rather than there keeps the
 * definition of "needs an operator" next to the words that describe it.
 */
type AdminNodeListProps = {
  nodes: NodeSummary[]
  /** Suspended or draining, straight from `ListNodes.needingAttention()`. */
  attention: NodeSummary[]
}

export default function AdminNodeListPage() {
  const {t} = useI18n()
  const {nodes, attention} = usePage<AdminNodeListProps>().props
  const [creating, setCreating] = useState(false)

  const flagged = flaggedNodes(nodes, attention)
  const settling = nodes.some(
    (node) => !node.converged || node.lifecycle === 'DRAINING' || node.lifecycle === 'CREATED',
  )
  useLiveNodes(['nodes', 'attention'], settling)

  const connected = nodes.filter((node) => node.connected).length
  const schedulable = nodes.filter(
    (node) => node.lifecycle === 'ENROLLED' && node.schedulable && node.connected,
  ).length
  const workloads = nodes.reduce((total, node) => total + node.runningWorkloadCount, 0)

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('node.list.title')} />

      <PageHeader
        title={t('node.list.title')}
        description={t('node.list.description')}
        actions={
          <Button block className="sm:w-auto" onClick={() => setCreating(true)}>
            {t('node.list.action.add')}
          </Button>
        }
      />

      <ClonedNodeAlert nodes={nodes} />

      {nodes.length > 0 ? (
        <dl className="grid grid-cols-3 gap-2 sm:gap-3">
          <FleetCount label={t('node.list.metric.connected')} value={`${connected}/${nodes.length}`} />
          <FleetCount label={t('node.list.metric.schedulable')} value={String(schedulable)} />
          <FleetCount label={t('node.list.metric.workloads')} value={String(workloads)} />
        </dl>
      ) : null}

      {flagged.length > 0 ? (
        <Card
          title={t('node.list.flagged.title')}
          description={t('node.list.flagged.description')}
          padded={false}
        >
          <NodeFleetList nodes={flagged} label={t('node.list.flagged.label')} />
        </Card>
      ) : null}

      <Card
        title={t('node.list.all.title')}
        description={t('node.list.all.machineCount', {count: nodes.length})}
        padded={false}
      >
        <NodeFleetList
          nodes={nodes}
          empty={
            <EmptyState
              icon={<Icon name="node" />}
              title={t('node.list.empty.title')}
              description={t('node.list.empty.description')}
              action={<Button onClick={() => setCreating(true)}>{t('node.list.empty.action')}</Button>}
            />
          }
        />
      </Card>

      <CreateNodeForm open={creating} onClose={() => setCreating(false)} />
    </div>
  )
}

/** One tile of the summary strip. Three across even at 375px; the numbers are short. */
function FleetCount({label, value}: {label: string; value: string}) {
  return (
    <div className="rounded-xl border border-ink-200 bg-white px-3 py-2.5 dark:border-ink-800 dark:bg-ink-900">
      <dt className="text-xs text-ink-500 dark:text-ink-400">{label}</dt>
      <dd className="mt-0.5 text-lg font-semibold tabular-nums text-ink-900 dark:text-ink-100">
        {value}
      </dd>
    </div>
  )
}

/**
 * The server's list, plus everything else worth a second look, in the server's order.
 *
 * Deduplicated by id: a suspended node with no gVisor matches both tests and is still one
 * machine.
 */
function flaggedNodes(nodes: NodeSummary[], attention: NodeSummary[]): NodeSummary[] {
  const flagged = new Map<string, NodeSummary>()
  for (const node of attention) {
    flagged.set(node.id, node)
  }
  for (const node of nodes) {
    if (needsAttention(node)) {
      flagged.set(node.id, node)
    }
  }
  return [...flagged.values()]
}
