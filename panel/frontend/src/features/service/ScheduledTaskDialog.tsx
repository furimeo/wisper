import {router} from '@inertiajs/react'

import {t} from '@/i18n'
import {Button, Checkbox, Input, Modal, Select, askConfirmation, useFormFields} from '@/shell'

import type {ConcurrencyPolicy, CronTaskView, ServiceView} from './serviceTypes'
import {SCHEDULE_PRESETS, TIMEOUT_CHOICES, describeSchedule} from './cronVocabulary'
import {concurrencyHint, concurrencyLabel} from './serviceVocabulary'

export function ScheduledTaskDialog({
  service,
  task,
  policies,
  onClose,
}: {
  service: ServiceView
  /** The task being edited, or null when this is a new one. */
  task: CronTaskView | null
  policies: ConcurrencyPolicy[]
  onClose: () => void
}) {
  const editing = task !== null
  const form = useFormFields({
    name: task?.name ?? '',
    schedule: task?.schedule ?? '0 3 * * *',
    timezone: task?.timezone ?? '',
    command: task?.command ?? '',
    timeoutSeconds: String(task?.timeoutSeconds ?? 300),
    concurrencyPolicy: task?.concurrencyPolicy ?? 'FORBID',
    enabled: task?.enabled ?? true,
  })

  const spoken = describeSchedule(form.data.schedule)
  const policy = form.data.concurrencyPolicy as ConcurrencyPolicy

  function save() {
    const url = editing
      ? `/services/${service.id}/tasks/update`
      : `/services/${service.id}/tasks`
    form.submit(url, {onSuccess: () => onClose()})
  }

  async function remove() {
    if (!task) {
      return
    }
    const confirmed = await askConfirmation({
      title: t('service.tasks.remove_confirm_title', {name: task.name}),
      body: t('service.tasks.remove_confirm_body'),
      confirmLabel: t('service.tasks.remove_confirm_button'),
      tone: 'danger',
    })
    if (confirmed) {
      router.post(
        `/services/${service.id}/tasks/delete`,
        {name: task.name},
        {preserveScroll: true, onSuccess: () => onClose()},
      )
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      title={editing ? task.name : t('service.tasks.dialog_new_title')}
      description={t('service.tasks.dialog_description')}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            {t('service.variables.cancel')}
          </Button>
          <Button loading={form.processing} onClick={save}>
            {editing ? t('service.variables.save') : t('service.tasks.schedule_it')}
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
          label={t('service.tasks.name_label')}
          required
          disabled={editing}
          maxLength={63}
          autoComplete="off"
          inputMode="url"
          spellCheck={false}
          placeholder={t('service.tasks.name_placeholder')}
          hint={
            editing
              ? t('service.tasks.name_hint_edit')
              : t('service.tasks.name_hint_new')
          }
        />

        <Input
          {...form.bind('command')}
          label={t('service.tasks.command_label')}
          required
          maxLength={4000}
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          className="font-mono"
          placeholder={t('service.tasks.command_placeholder')}
          hint={t('service.tasks.command_hint')}
        />

        <div className="flex flex-col gap-2">
          <Input
            {...form.bind('schedule')}
            label={t('service.tasks.schedule_label')}
            required
            maxLength={200}
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            className="font-mono"
            placeholder={t('service.tasks.schedule_placeholder')}
            hint={
              spoken === form.data.schedule
                ? t('service.tasks.schedule_default_hint')
                : spoken
            }
          />
          <div className="flex flex-wrap gap-1.5">
            {SCHEDULE_PRESETS.map((preset) => (
              <button
                key={preset.expression}
                type="button"
                onClick={() => form.set('schedule', preset.expression)}
                className="rounded-full border border-ink-300 px-3 py-1.5 text-xs text-ink-700 transition-colors hover:bg-ink-100 dark:border-ink-700 dark:text-ink-300 dark:hover:bg-ink-800"
              >
                {preset.label}
              </button>
            ))}
          </div>
        </div>

        <Input
          {...form.bind('timezone')}
          label={t('service.tasks.timezone_label')}
          maxLength={64}
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          placeholder={t('service.tasks.timezone_placeholder')}
          hint={t('service.tasks.timezone_hint')}
        />

        <Select
          {...form.bind('timeoutSeconds')}
          label={t('service.tasks.timeout_label')}
          options={TIMEOUT_CHOICES.map((choice) => ({
            value: String(choice.seconds),
            label: choice.label,
          }))}
          hint={t('service.tasks.timeout_hint')}
        />

        <Select
          {...form.bind('concurrencyPolicy')}
          label={t('service.tasks.concurrency_label')}
          options={policies.map((value) => ({value, label: concurrencyLabel(value)}))}
          hint={concurrencyHint(policy)}
        />

        <Checkbox
          {...form.check('enabled')}
          label={t('service.tasks.enabled_label')}
          hint={t('service.tasks.enabled_hint')}
        />

        {editing ? (
          <Button variant="danger" block onClick={() => void remove()} disabled={form.processing}>
            {t('service.tasks.remove_command_button')}
          </Button>
        ) : null}

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}
