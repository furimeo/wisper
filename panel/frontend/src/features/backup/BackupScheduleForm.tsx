import {t} from '@/i18n'
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
  const presets = [
    {value: '', label: t('backup.scheduleForm.presetNone')},
    {value: '0 3 * * *', label: t('backup.scheduleForm.presetNightly')},
    {value: '0 3 * * 0', label: t('backup.scheduleForm.presetSunday')},
    {value: '0 3 1 * *', label: t('backup.scheduleForm.presetMonthly')},
    {value: 'custom', label: t('backup.scheduleForm.presetCustom')},
  ]

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

  const preset = presets.some((option) => option.value === form.data.schedule)
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
      title={policy ? t('backup.scheduleForm.titleEdit', {name: policy.name}) : t('backup.scheduleForm.titleNew')}
      description={
        policy
          ? t('backup.scheduleForm.descEdit')
          : t('backup.scheduleForm.descNew')
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
            {policy ? t('backup.scheduleForm.save') : t('backup.scheduleForm.create')}
          </Button>
          <Button variant="ghost" block className="sm:w-auto" onClick={onClose}>
            {t('backup.scheduleForm.cancel')}
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
          label={t('backup.scheduleForm.name')}
          required
          maxLength={120}
          autoComplete="off"
          placeholder="Nightly database"
          hint={t('backup.scheduleForm.nameHint')}
        />

        {policy ? null : (
          <Select
            label={t('backup.scheduleForm.whatToCopy')}
            name="subjectId"
            required
            error={form.error('subjectId') ?? form.error('targetKind')}
            value={`${form.data.targetKind}:${form.data.subjectId}`}
            onChange={(event) => {
              const [kind = '', id = ''] = event.target.value.split(':')
              form.patch({targetKind: kind, subjectId: id})
            }}
            hint={t('backup.scheduleForm.whatToCopyHint')}
            options={targets.map((option) => ({
              value: `${option.kind}:${option.id}`,
              label: `${targetKindLabel(option.kind)} · ${option.label}${
                option.eligible ? '' : t('backup.scheduleForm.cannotBackup')
              }`,
              disabled: !option.eligible,
            }))}
          />
        )}

        <Select
          {...form.bind('destinationId')}
          label={t('backup.scheduleForm.whereToPut')}
          required
          options={usable.map((destination) => ({
            value: destination.id,
            label: `${destination.name}${destination.platformWide ? t('backup.scheduleForm.platformSuffix') : ''}${
              destination.provenReachable ? '' : t('backup.scheduleForm.neverCheckedSuffix')
            }`,
          }))}
          hint={
            usable.length === 0
              ? t('backup.scheduleForm.noDestHint')
              : t('backup.scheduleForm.destHint')
          }
        />

        <Select
          label={t('backup.scheduleForm.howOften')}
          value={preset}
          onChange={(event) =>
            form.set('schedule', event.target.value === 'custom' ? '0 3 * * *' : event.target.value)
          }
          options={presets}
          hint={scheduleSentence(form.data.schedule || null, form.data.timezone)}
        />

        {preset === 'custom' ? (
          <Input
            {...form.bind('schedule')}
            label={t('backup.scheduleForm.cronExpr')}
            className="font-mono"
            autoComplete="off"
            spellCheck={false}
            placeholder="0 3 * * *"
            hint={t('backup.scheduleForm.cronHint')}
          />
        ) : null}

        <Input
          {...form.bind('timezone')}
          label={t('backup.scheduleForm.timezone')}
          autoComplete="off"
          spellCheck={false}
          placeholder="UTC"
          hint={t('backup.scheduleForm.timezoneHint')}
        />

        <div className="grid grid-cols-2 gap-3">
          <Input
            {...form.bind('retentionCount')}
            type="number"
            inputMode="numeric"
            min={1}
            label={t('backup.scheduleForm.keepCount')}
            hint={t('backup.scheduleForm.keepCountHint')}
          />
          <Input
            {...form.bind('retentionDays')}
            type="number"
            inputMode="numeric"
            min={1}
            label={t('backup.scheduleForm.keepDays')}
            hint={t('backup.scheduleForm.keepDaysHint')}
          />
        </div>

        {policy ? (
          <Checkbox
            {...form.check('enabled')}
            label={t('backup.scheduleForm.enabled')}
            hint={t('backup.scheduleForm.enabledHint')}
          />
        ) : null}

        <button type="submit" className="sr-only">
          {policy ? t('backup.scheduleForm.save') : t('backup.scheduleForm.create')}
        </button>
      </form>
    </Modal>
  )
}
