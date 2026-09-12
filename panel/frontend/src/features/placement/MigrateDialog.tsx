import {Button, ByteSize, Checkbox, Field, Input, Modal, Select, useFormFields} from '@/shell'
import {t} from '@/i18n'

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
      title={t('placement.migrate.title', {service: row.serviceName})}
      description={t('placement.migrate.description', {node: row.nodeName, org: row.organizationName})}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            {t('placement.migrate.action.cancel')}
          </Button>
          <Button
            disabled={!submittable}
            onClick={() =>
              form.submit(`/admin/placement/${row.serviceId}/migrate`, {
                onSuccess: () => onClose(),
              })
            }
          >
            {t('placement.migrate.action.move')}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={t('placement.migrate.field.targetNode')} error={form.error('nodeId')}>
          <Select
            {...form.bind('nodeId')}
            options={elsewhere.map((node) => ({
              value: node.nodeId,
              label: isFull(node) ? t('placement.migrate.nodeFull', {name: node.name}) : node.name,
              disabled: isFull(node),
            }))}
          />
        </Field>

        {target ? <Headroom node={target} /> : null}

        {row.carryingData ? (
          <div className="rounded-xl border border-failed/50 bg-failed/10 px-4 py-3">
            <p className="text-sm leading-relaxed">
              {t('placement.migrate.dataWarning.prefix')} <ByteSize bytes={row.volumeBytes} />{' '}
              {t('placement.migrate.dataWarning.on', {node: row.nodeName})}{' '}
              <strong>{t('placement.migrate.dataWarning.bold')}</strong>{' '}
              {t('placement.migrate.dataWarning.suffix')}
            </p>
            <div className="mt-3">
              <Checkbox
                {...form.check('moveDespiteVolumes')}
                label={t('placement.migrate.checkbox')}
              />
            </div>
          </div>
        ) : null}

        <Field
          label={t('placement.migrate.field.reason')}
          hint={t('placement.migrate.field.reasonHint')}
          error={form.error('reason')}
        >
          <Input {...form.bind('reason')} placeholder={t('placement.migrate.field.reasonPlaceholder')} />
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
      {node.name} {t('placement.migrate.headroom.text1', {cpu: (free.cpuMillicores / 1000).toFixed(1)})}{' '}
      <ByteSize bytes={free.memoryBytes} /> {t('placement.migrate.headroom.text2')}{' '}
      <ByteSize bytes={free.diskBytes} /> {t('placement.migrate.headroom.text3')}
    </p>
  )
}
