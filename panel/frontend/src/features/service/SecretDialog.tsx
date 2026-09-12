import {router} from '@inertiajs/react'

import {t} from '@/i18n'
import {Button, Checkbox, Input, Modal, Textarea, askConfirmation, useFormFields} from '@/shell'

import type {SecretView, ServiceView} from './serviceTypes'

export function SecretDialog({
  service,
  secret,
  onClose,
}: {
  service: ServiceView
  /** The secret being rotated, or null when this is a new one. */
  secret: SecretView | null
  onClose: () => void
}) {
  const editing = secret !== null
  const form = useFormFields({
    name: secret?.name ?? '',
    value: '',
    buildTime: secret?.buildTime ?? service.site,
  })

  function save() {
    form.submit(`/services/${service.id}/environment/secrets`, {onSuccess: () => onClose()})
  }

  async function remove() {
    if (!secret) {
      return
    }
    const confirmed = await askConfirmation({
      title: t('service.secrets.remove_confirm_title', {name: secret.name}),
      body: t('service.secrets.remove_confirm_body'),
      confirmLabel: t('service.secrets.remove_confirm_button'),
      tone: 'danger',
    })
    if (confirmed) {
      router.post(
        `/services/${service.id}/environment/secrets/delete`,
        {name: secret.name},
        {preserveScroll: true, onSuccess: () => onClose()},
      )
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={editing ? t('service.secrets.dialog_rotate_title', {name: secret.name}) : t('service.secrets.dialog_new_title')}
      description={
        editing
          ? t('service.secrets.dialog_rotate_desc')
          : t('service.secrets.dialog_new_desc')
      }
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            {t('service.variables.cancel')}
          </Button>
          <Button loading={form.processing} onClick={save}>
            {editing ? t('service.secrets.replace_value') : t('service.secrets.add_secret')}
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
          label={t('service.variables.name_label')}
          required
          disabled={editing}
          maxLength={256}
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          className="font-mono"
          placeholder={t('service.secrets.name_placeholder')}
          hint={
            editing
              ? t('service.variables.name_hint_edit')
              : t('service.secrets.name_hint_new')
          }
        />

        <Textarea
          {...form.bind('value')}
          label={editing ? t('service.secrets.value_label_edit') : t('service.secrets.value_label_new')}
          required
          maxLength={32768}
          spellCheck={false}
          autoComplete="off"
          className="font-mono"
          hint={t('service.secrets.value_hint')}
        />

        <Checkbox
          {...form.check('buildTime')}
          disabled={service.site}
          label={t('service.variables.build_time_label')}
          hint={
            service.site
              ? t('service.variables.build_time_hint_site')
              : t('service.secrets.build_time_hint_app')
          }
        />

        {editing ? (
          <Button variant="danger" block onClick={() => void remove()} disabled={form.processing}>
            {t('service.secrets.remove_button')}
          </Button>
        ) : null}

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}
