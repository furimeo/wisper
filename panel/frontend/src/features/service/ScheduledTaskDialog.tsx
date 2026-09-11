import {router} from '@inertiajs/react'

import {Button, Checkbox, Input, Modal, Select, askConfirmation, useFormFields} from '@/shell'

import type {ConcurrencyPolicy, CronTaskView, ServiceView} from './serviceTypes'
import {SCHEDULE_PRESETS, TIMEOUT_CHOICES, describeSchedule} from './cronVocabulary'
import {concurrencyHint, concurrencyLabel} from './serviceVocabulary'

/**
 * Scheduling a command, and editing or removing one that exists.
 *
 * The expression is typed, not built out of six dropdowns. Everyone who schedules
 * anything already has a crontab line to paste, and a builder that cannot express "every
 * quarter hour between nine and five on weekdays" would send them back to a text box
 * anyway. What the dialog adds is the sentence underneath, recomputed as they type: a
 * wrong field is obvious in words and invisible in five numbers.
 *
 * The name is the key `UpdateScheduledTask` looks the entry up by, so it is fixed once the
 * task exists. Renaming is delete-then-add, which is what it actually is.
 *
 * The timezone is a plain input rather than a list of six hundred IANA names in a
 * `<select>` a phone renders as a wheel. Left empty it is UTC, which is what the server
 * does with it too.
 */
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
      title: `Remove ${task.name}?`,
      body: 'The command stops being scheduled, and its run history goes with it.',
      confirmLabel: 'Remove it',
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
      title={editing ? task.name : 'New scheduled command'}
      description="The node runs it inside this service's container and keeps the schedule even while the panel is unreachable."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            Cancel
          </Button>
          <Button loading={form.processing} onClick={save}>
            {editing ? 'Save' : 'Schedule it'}
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
          required
          disabled={editing}
          maxLength={63}
          autoComplete="off"
          inputMode="url"
          spellCheck={false}
          placeholder="nightly-cleanup"
          hint={
            editing
              ? 'A name cannot be changed: it is what identifies this entry. Remove it and add the new name.'
              : 'Lower-case letters, digits and dashes. It is how this entry is identified.'
          }
        />

        <Input
          {...form.bind('command')}
          label="Command"
          required
          maxLength={4000}
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          className="font-mono"
          placeholder="php artisan queue:prune-batches"
          hint="Split into arguments the way a shell would, then run directly - there is no shell in between, so pipes and redirects do not work here."
        />

        <div className="flex flex-col gap-2">
          <Input
            {...form.bind('schedule')}
            label="Schedule"
            required
            maxLength={200}
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            className="font-mono"
            placeholder="0 3 * * *"
            hint={
              spoken === form.data.schedule
                ? 'Five fields: minute, hour, day of month, month, day of week.'
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
          label="Timezone"
          maxLength={64}
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          placeholder="UTC"
          hint="An IANA name such as Asia/Ho_Chi_Minh. Left empty it is UTC - which is what a job that must not move twice a year wants."
        />

        <Select
          {...form.bind('timeoutSeconds')}
          label="Give up after"
          options={TIMEOUT_CHOICES.map((choice) => ({
            value: String(choice.seconds),
            label: choice.label,
          }))}
          hint="The node kills the run at this point and records it as failed."
        />

        <Select
          {...form.bind('concurrencyPolicy')}
          label="If the last run is still going"
          options={policies.map((value) => ({value, label: concurrencyLabel(value)}))}
          hint={concurrencyHint(policy)}
        />

        <Checkbox
          {...form.check('enabled')}
          label="Scheduled"
          hint="Off keeps the entry and its history but stops the node running it."
        />

        {editing ? (
          <Button variant="danger" block onClick={() => void remove()} disabled={form.processing}>
            Remove this command
          </Button>
        ) : null}

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}
