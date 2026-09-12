import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {Badge, ByteSize, Card, DataList, EmptyState, PageHeader, RelativeTime} from '@/shell'
import {t} from '@/i18n'

import {MigrateDialog} from './MigrateDialog'
import type {NodeCapacity, PlacedServiceRow} from './placementTypes'
import {groupByNode, headroomOf, isFull} from './placementTypes'

/**
 * `GET /admin/placement` - who is running where, and the only way to change it.
 *
 * <p>Grouped by node rather than listed flat, because every question an operator brings
 * here is about a machine: this one is being retired, that one is full, this one is
 * behaving oddly and I want to know who is affected. A flat table sorted by service would
 * answer none of them without scrolling.
 *
 * <p>Pinned is the fact that matters. The scheduler placed each of these once and will
 * never move a pinned one on its own, because the volume does not follow - so a node
 * cannot be emptied by waiting, and the button on each row is the whole mechanism.
 */
type AdminPlacementProps = {
  placements: PlacedServiceRow[]
  /** Every schedulable node, including the full ones. */
  nodes: NodeCapacity[]
}

export default function AdminPlacementPage() {
  const {placements, nodes} = usePage<AdminPlacementProps>().props
  const [moving, setMoving] = useState<PlacedServiceRow | null>(null)

  const groups = groupByNode(placements)
  const idle = nodes.filter((node) => !placements.some((row) => row.nodeId === node.nodeId))

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('placement.title')} />

      <PageHeader
        title={t('placement.title')}
        description={t('placement.description')}
      />

      {placements.length === 0 ? (
        <EmptyState
          title={t('placement.empty.title')}
          description={t('placement.empty.description')}
        />
      ) : null}

      {groups.map((group) => {
        const node = nodes.find((candidate) => candidate.nodeId === group.nodeId) ?? null
        return (
          <Card
            key={group.nodeId}
            title={group.nodeName}
            description={node ? describe(node, group.rows.length) : t('placement.describe.placed', {count: group.rows.length})}
          >
            <DataList
              items={group.rows}
              label={t('placement.unit')}
              keyOf={(row) => `${row.serviceId}:${row.nodeId}`}
              primary={(row) => row.serviceName}
              secondary={(row) => `${row.organizationName} · ${row.projectSlug}/${row.serviceSlug}`}
              trailing={(row) => <StateBadge row={row} />}
              actions={(row) =>
                row.movable
                  ? [{label: t('placement.action.moveToAnother'), onSelect: () => setMoving(row)}]
                  : []
              }
              columns={[
                {key: 'service', header: t('placement.column.service'), cell: (row) => row.serviceName},
                {
                  key: 'tenant',
                  header: t('placement.column.tenant'),
                  cell: (row) => `${row.organizationName} · ${row.projectSlug}`,
                },
                {key: 'kind', header: t('placement.column.kind'), cell: (row) => row.kind.toLowerCase()},
                {key: 'state', header: t('placement.column.state'), cell: (row) => <StateBadge row={row} />},
                {
                  key: 'data',
                  header: t('placement.column.data'),
                  align: 'right',
                  cell: (row) =>
                    row.carryingData ? <ByteSize bytes={row.volumeBytes} /> : t('placement.data.none'),
                },
                {
                  key: 'since',
                  header: t('placement.column.placed'),
                  cell: (row) => <RelativeTime at={row.placedAt} />,
                },
              ]}
            />
          </Card>
        )
      })}

      {idle.length > 0 ? (
        <Card
          title={t('placement.idle.title')}
          description={t('placement.idle.description')}
        >
          <ul className="flex flex-wrap gap-2">
            {idle.map((node) => (
              <li key={node.nodeId}>
                <Badge tone={isFull(node) ? 'degraded' : 'neutral'}>{node.name}</Badge>
              </li>
            ))}
          </ul>
        </Card>
      ) : null}

      {moving ? (
        <MigrateDialog row={moving} nodes={nodes} onClose={() => setMoving(null)} />
      ) : null}
    </div>
  )
}

function describe(node: NodeCapacity, placed: number): string {
  const free = headroomOf(node)
  const held = t('placement.describe.held', {count: placed})
  if (isFull(node)) {
    return t('placement.describe.full', {held})
  }
  return t('placement.describe.free', {held, cpu: (free.cpuMillicores / 1000).toFixed(1)})
}

/**
 * The row's state, with pinning folded in.
 *
 * <p>Pinned is not a state of the placement, it is a property of it - but on this page it
 * is the one an operator scans for, because it separates "the scheduler could move this"
 * from "only you can, and the data stays".
 */
function StateBadge({row}: {row: PlacedServiceRow}) {
  if (row.draining) {
    return <Badge tone="degraded">{t('placement.state.draining')}</Badge>
  }
  if (row.state === 'PLANNED') {
    return <Badge tone="neutral">{t('placement.state.planned')}</Badge>
  }
  return (
    <Badge tone={row.pinned ? 'accent' : 'running'}>
      {row.pinned ? t('placement.state.pinned') : t('placement.state.active')}
    </Badge>
  )
}
