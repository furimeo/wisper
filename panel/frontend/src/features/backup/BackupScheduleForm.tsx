import {Button, Checkbox, Input, Modal, Select, useFormFields} from '@/shell'

import type {BackupTargetOption, BackupView, DestinationView} from './backupTypes'
import {scheduleSentence, targetKindLabel} from './backupVocabulary'

/**
 * Setting up what gets copied, and how often.
 *
 * Creating and editing are one form because they are one set of decisions; the only
 * difference is that the target cannot be changed afterwards, since a policy's snapshots
 * belong to the thing it was pointed at. The server does not accept a target on update
 * either.
 *
 * The schedule is a short list of shapes plus a cron box, and not a cron box alone. Most
 * people want "every night" and should not have to know that is `0 3 * * *`; the ones who
 * want a six-hourly expression of their own can still have it, and the sentence under the
 * field says back what the panel understood - so a mistyped expression is caught here
 * rather than on the night it does not fire.
 *
 * Retention is two numbers on purpose. A count alone loses a month of history the week
 * somebody sets a policy to hourly; days alone lose everything if a job stops running.
 */
interface ScheduleValues {
  name: string
  destinationId: string
  targetKind: string
  subjectId: string
  schedule: string
  timezone: string
  retentionCount: string
  retentionDays: string
  enabled: boolean
  [key: string]: string | boolean
}

const PRESETS: Array<{value: string; label: string}> = [
  {value: '', label: 'No schedule - only when I press the button'},
  {value: '0 3 * * *', label: 'Every night at 03:00'},
  {value: '0 3 * * 0', label: 'Every Sunday at 03:00'},
  {value: '0 3 1 * *', label: 'On the 1st of each month at 03:00'},
  {value: 'custom', label: 'A cron expression of my own'},
]

export function BackupScheduleForm({
  open,
  onClose,
  organizationId,
  policy,
  destinations,
  targets,
}: {
  open: boolean
  onClose: () => void
  organizationId: string
  /** Null when creating. */
  policy: BackupView | null
  destinations: DestinationView[]
  targets: BackupTargetOption[]
}) {
  const usable = destinations.filter((destination) => destination.enabled)
  const first = targets.find((option) => option.eligible) ?? targets[0]
  const form = useFormFields<ScheduleValues>({
    name: policy?.name ?? '',
    destinationId: policy?.destinationId ?? usable[0]?.id ?? '',
    targetKind: first?.kind ?? '',
    subjectId: first?.id ?? '',
    schedule: policy?.schedule ?? '0 3 * * *',
    timezone: policy?.timezone ?? 'UTC',
    retentionCount: String(policy?.retentionCount ?? 7),
    retentionDays: String(policy?.retentionDays ?? 30),
    enabled: policy?.enabled ?? true,
  })

  const preset = PRESETS.some((option) => option.value === form.data.schedule)
    ? form.data.schedule
    : 'custom'

  /*
   * The target is two parameters on the wire and one control on the screen, so the select
   * carries `KIND:id` and splits it back on change. The update endpoint reads neither -
   * a policy's snapshots belong to what it was pointed at - and sending them anyway costs
   * two ignored request parameters.
   */
  function submit() {
    const path = policy
      ? `/backups/${organizationId}/policies/${policy.id}`
      : `/backups/${organizationId}/policies`

    form.submit(path, {
      onSuccess: () => {
        form.reset()
        onClose()
      },
    })
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={policy ? `Edit “${policy.name}”` : 'New backup schedule'}
      description={
        policy
          ? 'What it copies cannot be changed - the snapshots it has already taken belong to that. Everything else can.'
          : 'Pick what to copy, where to put it, and how often. The node does the work and pushes straight to the destination; nothing passes through the panel.'
      }
      size="lg"
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button
            block
            className="sm:w-auto"
            loading={form.processing}
            disabled={usable.length === 0}
            onClick={submit}
          >
            {policy ? 'Save' : 'Create it'}
          </Button>
          <Button variant="ghost" block className="sm:w-auto" onClick={onClose}>
            Cancel
          </Button>
        </div>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <Input
          {...form.bind('name')}
          label="Name"
          required
          maxLength={120}
          autoComplete="off"
          placeholder="Nightly database"
          hint="You type this back to delete the policy, so make it one you would recognise."
        />

        {policy ? null : (
          <Select
            label="What to copy"
            name="subjectId"
            required
            error={form.error('subjectId') ?? form.error('targetKind')}
            value={`${form.data.targetKind}:${form.data.subjectId}`}
            onChange={(event) => {
              const [kind = '', id = ''] = event.target.value.split(':')
              form.patch({targetKind: kind, subjectId: id})
            }}
            hint="A volume is copied with a short pause on writes; a database is dumped logically."
            options={targets.map((option) => ({
              value: `${option.kind}:${option.id}`,
              label: `${targetKindLabel(option.kind)} · ${option.label}${
                option.eligible ? '' : ' (cannot be backed up right now)'
              }`,
              disabled: !option.eligible,
            }))}
          />
        )}

        <Select
          {...form.bind('destinationId')}
          label="Where to put it"
          required
          options={usable.map((destination) => ({
            value: destination.id,
            label: `${destination.name}${destination.platformWide ? ' (platform)' : ''}${
              destination.provenReachable ? '' : ' - never checked'
            }`,
          }))}
          hint={
            usable.length === 0
              ? 'There is no enabled destination to write to. Add one under Destinations first.'
              : 'Check a destination before you rely on it - an unwritable bucket fails at three in the morning.'
          }
        />

        <Select
          label="How often"
          value={preset}
          onChange={(event) =>
            form.set('schedule', event.target.value === 'custom' ? '0 3 * * *' : event.target.value)
          }
          options={PRESETS}
          hint={scheduleSentence(form.data.schedule || null, form.data.timezone)}
        />

        {preset === 'custom' ? (
          <Input
            {...form.bind('schedule')}
            label="Cron expression"
            className="font-mono"
            autoComplete="off"
            spellCheck={false}
            placeholder="0 3 * * *"
            hint="Five fields: minute, hour, day of month, month, day of week."
          />
        ) : null}

        <Input
          {...form.bind('timezone')}
          label="Time zone"
          autoComplete="off"
          spellCheck={false}
          placeholder="UTC"
          hint="An IANA name, such as Europe/Berlin. The schedule above is read in it."
        />

        <div className="grid grid-cols-2 gap-3">
          <Input
            {...form.bind('retentionCount')}
            type="number"
            inputMode="numeric"
            min={1}
            label="Keep this many"
            hint="Newest first."
          />
          <Input
            {...form.bind('retentionDays')}
            type="number"
            inputMode="numeric"
            min={1}
            label="Keep for days"
            hint="Whichever runs out first."
          />
        </div>

        {policy ? (
          <Checkbox
            {...form.check('enabled')}
            label="Run on the schedule"
            hint="Off stops new snapshots. The ones already taken stay and are still restorable."
          />
        ) : null}

        <button type="submit" className="sr-only">
          {policy ? 'Save policy' : 'Create policy'}
        </button>
      </form>
    </Modal>
  )
}
