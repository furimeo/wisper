import {router} from '@inertiajs/react'

import {t} from '@/i18n'
import {Button, Checkbox, Input, Modal, Textarea, askConfirmation, useFormFields} from '@/shell'

import type {EnvVar, ServiceView} from './serviceTypes'

export function EnvVarDialog({
  service,
  variable,
  onClose,
}: {
  service: ServiceView
  /** The variable being changed, or null when this is a new one. */
  variable: EnvVar | null
  onClose: () => void
}) {
  const editing = variable !== null
  const form = useFormFields({
    name: variable?.name ?? '',
    value: variable?.value ?? '',
    buildTime: variable?.buildTime ?? service.site,
  })

  function save() {
    form.submit(`/services/${service.id}/environment/variables`, {onSuccess: () => onClose()})
  }

  async function remove() {
    if (!variable) {
      return
    }
    const confirmed = await askConfirmation({
      title: t('service.variables.remove_confirm_title', {name: variable.name}),
      body: t('service.variables.remove_confirm_body'),
      confirmLabel: t('service.variables.remove_confirm_button'),
      tone: 'danger',
    })
    if (confirmed) {
      router.post(
        `/services/${service.id}/environment/variables/delete`,
        {name: variable.name},
        {preserveScroll: true, onSuccess: () => onClose()},
      )
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={editing ? variable.name : t('service.variables.dialog_new_title')}
      description={
        editing
          ? t('service.variables.dialog_edit_desc')
          : t('service.variables.dialog_new_desc')
      }
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            {t('service.variables.cancel')}
          </Button>
          <Button loading={form.processing} onClick={save}>
            {editing ? t('service.variables.save') : t('service.variables.add_variable')}
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
          placeholder={t('service.variables.name_placeholder')}
          hint={
            editing
              ? t('service.variables.name_hint_edit')
              : t('service.variables.name_hint_new')
          }
        />

        <Textarea
          {...form.bind('value')}
          label={t('service.variables.value_label')}
          maxLength={32768}
          spellCheck={false}
          className="font-mono"
          hint={t('service.variables.value_hint')}
        />

        <Checkbox
          {...form.check('buildTime')}
          disabled={service.site}
          label={t('service.variables.build_time_label')}
          hint={
            service.site
              ? t('service.variables.build_time_hint_site')
              : t('service.variables.build_time_hint_app')
          }
        />

        {editing ? (
          <Button variant="danger" block onClick={() => void remove()} disabled={form.processing}>
            {t('service.variables.remove_button')}
          </Button>
        ) : null}

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}
