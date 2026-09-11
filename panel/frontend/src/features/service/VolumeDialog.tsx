import {router} from '@inertiajs/react'

import {Button, Checkbox, Input, Modal, askConfirmation, formatBytes, useFormFields} from '@/shell'

import {ByteAmountField} from './ByteAmountField'
import type {ServiceView, Volume} from './serviceTypes'

/**
 * Attaching a volume, and the two things that can be done to one that exists.
 *
 * Creating asks for four things; resizing asks for one. They share a dialog because they
 * share the size control and the sentence explaining what a quota on a disk means, and
 * because the second is reached by tapping the volume the first created.
 *
 * The name and the mount path are fixed once the volume exists. `ResizeVolume` takes the
 * name as its key and there is no use-case that moves a mount point - the data is on the
 * node under that path, and changing the label in the panel would not move it.
 *
 * Sizes are typed in MiB or GiB and submitted as `sizeMebibytes`, which is what
 * `VolumeController` binds; it converts to bytes in one place so a factor of 1024 cannot
 * be applied twice. Refusals come back on the same key, because `RequestRejected` carries
 * the name of the input at fault and the input is the one the customer typed into.
 */
const MEBIBYTE = 1_048_576

export function VolumeDialog({
  service,
  volume,
  onClose,
}: {
  service: ServiceView
  /** The volume being changed, or null when this is a new one. */
  volume: Volume | null
  onClose: () => void
}) {
  const editing = volume !== null
  const form = useFormFields({
    name: volume?.name ?? '',
    mountPath: volume?.mountPath ?? '',
    sizeMebibytes: String(Math.max(1, Math.round((volume?.sizeBytes ?? 1024 * MEBIBYTE) / MEBIBYTE))),
    readOnly: volume?.readOnly ?? false,
    backupEnabled: volume?.backupEnabled ?? true,
  })

  const sizeError = form.error('sizeMebibytes')

  function save() {
    const url = editing
      ? `/services/${service.id}/volumes/resize`
      : `/services/${service.id}/volumes`
    form.submit(url, {onSuccess: () => onClose()})
  }

  async function remove() {
    if (!volume) {
      return
    }
    const confirmed = await askConfirmation({
      title: `Detach ${volume.name}?`,
      body:
        `Nothing is mounted at ${volume.mountPath} afterwards. The data stays on the node ` +
        'until it is purged, but the service loses its way to it and is no longer pinned by it.',
      confirmLabel: 'Detach volume',
      tone: 'danger',
      requireText: volume.name,
      requireTextLabel: `Type ${volume.name} to confirm`,
    })
    if (confirmed) {
      router.post(
        `/services/${service.id}/volumes/delete`,
        {name: volume.name, confirmation: volume.name},
        {preserveScroll: true, onSuccess: () => onClose()},
      )
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={editing ? volume.name : 'Attach a volume'}
      description={
        editing
          ? `Mounted at ${volume.mountPath}. The quota is what the node enforces on the directory.`
          : 'A directory on the node that survives restarts, redeploys and image changes.'
      }
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            Cancel
          </Button>
          <Button loading={form.processing} onClick={save}>
            {editing ? 'Save size' : 'Attach volume'}
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          save()
        }}
      >
        <Input
          {...form.bind('name')}
          label="Name"
          disabled={editing}
          maxLength={63}
          autoComplete="off"
          inputMode="url"
          spellCheck={false}
          placeholder="data"
          hint={
            editing
              ? 'A name cannot be changed: it is what identifies the directory on the node.'
              : 'Lower-case letters, digits and dashes. Left empty it becomes "data".'
          }
        />

        <Input
          {...form.bind('mountPath')}
          label="Mount path"
          required={!editing}
          disabled={editing}
          maxLength={512}
          autoComplete="off"
          spellCheck={false}
          className="font-mono"
          placeholder="/data"
          hint={
            editing
              ? 'A mount path cannot be moved. Detach this volume and attach a new one to change it.'
              : 'An absolute Linux path inside the container. Not /, /etc, /usr or anywhere the image keeps its own files.'
          }
        />

        <ByteAmountField
          label="Size"
          name="sizeMebibytes"
          bytes={toBytes(form.data.sizeMebibytes)}
          onBytes={(bytes) => form.set('sizeMebibytes', toMebibytes(bytes))}
          error={sizeError}
          hint={sizeHint(volume)}
        />

        <Checkbox
          {...form.check('readOnly')}
          disabled={editing}
          label="Mount read-only"
          hint={
            editing
              ? 'Fixed once attached, because a workload that opened it for writing would have to be restarted anyway.'
              : 'For a volume the container only reads - configuration, a shared asset bundle.'
          }
        />

        <Checkbox
          {...form.check('backupEnabled')}
          disabled={editing}
          label="Include in scheduled backups"
          hint={
            editing
              ? 'Set when the volume is attached. Detach and attach again to change it.'
              : 'On unless this holds a cache you would rather not pay to store twice.'
          }
        />

        {editing ? (
          <Button variant="danger" block onClick={() => void remove()} disabled={form.processing}>
            Detach this volume
          </Button>
        ) : null}

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}

/** What the customer is told about the range, and about what is already on the disk. */
function sizeHint(volume: Volume | null): string {
  const range = 'Between 1 MiB and 4 TiB, counted against your organization’s disk quota.'
  if (volume?.usedBytes == null) {
    return range
  }
  return `${range} ${formatBytes(volume.usedBytes)} is already stored, so it cannot go below that.`
}

function toBytes(mebibytes: string): string {
  const parsed = Number.parseInt(mebibytes, 10)
  return Number.isFinite(parsed) && parsed > 0 ? String(parsed * MEBIBYTE) : ''
}

function toMebibytes(bytes: string): string {
  const parsed = Number.parseInt(bytes, 10)
  return Number.isFinite(parsed) && parsed > 0 ? String(Math.max(1, Math.round(parsed / MEBIBYTE))) : ''
}
