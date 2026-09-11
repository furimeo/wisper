/**
 * What `AdminPlacementController` puts in the model.
 *
 * Typed against the Java records. `PlacedServiceRow.isMovable()` and `isCarryingData()`
 * serialise as `movable` and `carryingData`; `NodeCapacity`'s `usableXxx()` and `freeXxx()`
 * have no `is`/`get` prefix and are not record components, so Jackson does not emit them
 * and they are recomputed below. The arithmetic is one subtraction and duplicating it here
 * beats widening a record the scheduler also uses.
 */

/** `PlacedServiceRow` - one service and the node holding it. */
export interface PlacedServiceRow {
  serviceId: string
  serviceName: string
  serviceSlug: string
  kind: string
  desiredState: string
  projectId: string
  projectSlug: string
  organizationId: string
  organizationName: string
  nodeId: string
  nodeName: string
  /** PLANNED, ACTIVE or DRAINING. RELEASED rows are history and never reach this page. */
  state: string
  /** A volume exists on this node, so the scheduler will never move it on its own. */
  pinned: boolean
  /** Already on its way somewhere else. */
  draining: boolean
  volumeBytes: number
  placedAt: string
  reason: string
  /** `isMovable()`: not already draining. */
  movable: boolean
  /** `isCarryingData()`: moving it leaves bytes behind. */
  carryingData: boolean
}

/** `NodeCapacity` - a schedulable node and what is already spoken for on it. */
export interface NodeCapacity {
  nodeId: string
  name: string
  cpuMillicoresTotal: number
  cpuMillicoresReserved: number
  cpuMillicoresCommitted: number
  memoryBytesTotal: number
  memoryBytesReserved: number
  memoryBytesCommitted: number
  diskBytesTotal: number
  diskBytesReserved: number
  diskBytesCommitted: number
}

/** What is left after the headroom reservation - `NodeCapacity.freeXxx()`. */
export interface NodeHeadroom {
  cpuMillicores: number
  memoryBytes: number
  diskBytes: number
}

export function headroomOf(node: NodeCapacity): NodeHeadroom {
  return {
    cpuMillicores: Math.max(
      0,
      node.cpuMillicoresTotal - node.cpuMillicoresReserved - node.cpuMillicoresCommitted,
    ),
    memoryBytes: Math.max(
      0,
      node.memoryBytesTotal - node.memoryBytesReserved - node.memoryBytesCommitted,
    ),
    diskBytes: Math.max(0, node.diskBytesTotal - node.diskBytesReserved - node.diskBytesCommitted),
  }
}

/** Whether a node has room for anything at all, which decides how it is drawn. */
export function isFull(node: NodeCapacity): boolean {
  const free = headroomOf(node)
  return free.cpuMillicores <= 0 || free.memoryBytes <= 0 || free.diskBytes <= 0
}

/** The placements on one node, in the order the query returned them. */
export function groupByNode(
  placements: PlacedServiceRow[],
): Array<{nodeId: string; nodeName: string; rows: PlacedServiceRow[]}> {
  const order: string[] = []
  const byNode = new Map<string, {nodeId: string; nodeName: string; rows: PlacedServiceRow[]}>()
  for (const row of placements) {
    let group = byNode.get(row.nodeId)
    if (!group) {
      group = {nodeId: row.nodeId, nodeName: row.nodeName, rows: []}
      byNode.set(row.nodeId, group)
      order.push(row.nodeId)
    }
    group.rows.push(row)
  }
  return order.map((id) => byNode.get(id)!)
}
