import {Button, ByteSize, Checkbox, Field, Input, Modal, Select, useFormFields} from '@/shell'

import type {NodeCapacity, PlacedServiceRow} from './placementTypes'
import {headroomOf, isFull} from './placementTypes'

/**
 * Moving one service to another node.
 *
 * <p>Two separate acknowledgements, deliberately not merged. Choosing a target says where
 * it should run; the checkbox says the volume is staying behind. A pinned service moved by
 * somebody who only read the first question is a customer whose data is on a machine their
 * application can no longer reach - and the panel cannot undo it, because moving the bytes
 * is a backup and a restore, not a placement.
 *
 * <p>Full nodes are listed and disabled rather than hidden. An operator looking for the
 * node they had in mind needs to see that it is full; a shorter list would just look like
 * the node had been deleted.
 */
export function MigrateDialog({
  row,
  nodes,
  onClose,
}: {
  row: PlacedServiceRow
  nodes: NodeCapacity[]
  onClose: () => void
}) {
  const elsewhere = nodes.filter((node) => node.nodeId !== row.nodeId)
  const form = useFormFields({
    nodeId: elsewhere.find((node) => !isFull(node))?.nodeId ?? '',
    moveDespiteVolumes: false,
    reason: '',
  })

  const target = elsewhere.find((node) => node.nodeId === form.data.nodeId) ?? null
  const blocked = row.carryingData && !form.data.moveDespiteVolumes
  const submittable = form.data.nodeId !== '' && !blocked

  return (
    <Modal
      open
      onClose={onClose}
      title={`Move ${row.serviceName}`}
      description={`Currently on ${row.nodeName}, in ${row.organizationName}.`}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button
            disabled={!submittable}
            onClick={() =>
              form.submit(`/admin/placement/${row.serviceId}/migrate`, {
                onSuccess: () => onClose(),
              })
            }
          >
            Move it
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label="Target node" error={form.error('nodeId')}>
          <Select
            {...form.bind('nodeId')}
            options={elsewhere.map((node) => ({
              value: node.nodeId,
              label: isFull(node) ? `${node.name} — full` : node.name,
              disabled: isFull(node),
            }))}
          />
        </Field>

        {target ? <Headroom node={target} /> : null}

        {row.carryingData ? (
          <div className="rounded-xl border border-failed/50 bg-failed/10 px-4 py-3">
            <p className="text-sm leading-relaxed">
              This service holds <ByteSize bytes={row.volumeBytes} /> on {row.nodeName}.{' '}
              <strong>The data does not move with it.</strong> The new node starts with empty
              volumes, and recovering the contents means restoring a backup onto it.
            </p>
            <div className="mt-3">
              <Checkbox
                {...form.check('moveDespiteVolumes')}
                label="I accept that the data stays on the old node"
              />
            </div>
          </div>
        ) : null}

        <Field
          label="Reason"
          hint="Recorded in the audit trail and in the placement itself. Optional, and worth writing."
          error={form.error('reason')}
        >
          <Input {...form.bind('reason')} placeholder="draining node-3 for a disk swap" />
        </Field>
      </div>
    </Modal>
  )
}

/** What the target has left after its headroom reservation. */
function Headroom({node}: {node: NodeCapacity}) {
  const free = headroomOf(node)
  return (
    <p className="text-sm text-ink-500 dark:text-ink-400">
      {node.name} has {(free.cpuMillicores / 1000).toFixed(1)} vCPU,{' '}
      <ByteSize bytes={free.memoryBytes} /> of memory and <ByteSize bytes={free.diskBytes} /> of
      disk free, after the reserve the scheduler keeps back.
    </p>
  )
}
