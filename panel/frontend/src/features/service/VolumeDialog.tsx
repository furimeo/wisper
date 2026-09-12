import {router} from '@inertiajs/react'

import {t} from '@/i18n'
import {Button, Checkbox, Input, Modal, askConfirmation, formatBytes, useFormFields} from '@/shell'

import {ByteAmountField} from './ByteAmountField'
import type {ServiceView, Volume} from './serviceTypes'

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
      title: t('service.volumes.detach_confirm_title', {name: volume.name}),
      body: t('service.volumes.detach_confirm_body', {mountPath: volume.mountPath}),
      confirmLabel: t('service.volumes.detach_confirm_button'),
      tone: 'danger',
      requireText: volume.name,
      requireTextLabel: t('service.volumes.type_to_confirm', {name: volume.name}),
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
      title={editing ? volume.name : t('service.volumes.dialog_attach_title')}
      description={
        editing
          ? t('service.volumes.dialog_edit_desc', {mountPath: volume.mountPath})
          : t('service.volumes.dialog_new_desc')
      }
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            {t('service.variables.cancel')}
          </Button>
          <Button loading={form.processing} onClick={save}>
            {editing ? t('service.volumes.save_size') : t('service.volumes.attach_volume')}
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
          label={t('service.volumes.name_label')}
          disabled={editing}
          maxLength={63}
          autoComplete="off"
          inputMode="url"
          spellCheck={false}
          placeholder={t('service.volumes.name_placeholder')}
          hint={
            editing
              ? t('service.volumes.name_hint_edit')
              : t('service.volumes.name_hint_new')
          }
        />

        <Input
          {...form.bind('mountPath')}
          label={t('service.volumes.mount_path_label')}
          required={!editing}
          disabled={editing}
          maxLength={512}
          autoComplete="off"
          spellCheck={false}
          className="font-mono"
          placeholder={t('service.volumes.mount_path_placeholder')}
          hint={
            editing
              ? t('service.volumes.mount_path_hint_edit')
              : t('service.volumes.mount_path_hint_new')
          }
        />

        <ByteAmountField
          label={t('service.volumes.size_label')}
          name="sizeMebibytes"
          bytes={toBytes(form.data.sizeMebibytes)}
          onBytes={(bytes) => form.set('sizeMebibytes', toMebibytes(bytes))}
          error={sizeError}
          hint={sizeHint(volume)}
        />

        <Checkbox
          {...form.check('readOnly')}
          disabled={editing}
          label={t('service.volumes.mount_read_only')}
          hint={
            editing
              ? t('service.volumes.read_only_hint_edit')
              : t('service.volumes.read_only_hint_new')
          }
        />

        <Checkbox
          {...form.check('backupEnabled')}
          disabled={editing}
          label={t('service.volumes.include_backups')}
          hint={
            editing
              ? t('service.volumes.backups_hint_edit')
              : t('service.volumes.backups_hint_new')
          }
        />

        {editing ? (
          <Button variant="danger" block onClick={() => void remove()} disabled={form.processing}>
            {t('service.volumes.detach_button')}
          </Button>
        ) : null}

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}

function sizeHint(volume: Volume | null): string {
  if (volume?.usedBytes == null) {
    return t('service.volumes.size_hint_range')
  }
  return t('service.volumes.size_hint_with_used', {used: formatBytes(volume.usedBytes)})
}

function toBytes(mebibytes: string): string {
  const parsed = Number.parseInt(mebibytes, 10)
  return Number.isFinite(parsed) && parsed > 0 ? String(parsed * MEBIBYTE) : ''
}

function toMebibytes(bytes: string): string {
  const parsed = Number.parseInt(bytes, 10)
  return Number.isFinite(parsed) && parsed > 0 ? String(Math.max(1, Math.round(parsed / MEBIBYTE))) : ''
}
