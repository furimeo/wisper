import {t} from '@/i18n'
import {Input, Select} from '@/shell'
import type {FormFields} from '@/shell'

import {AppQuickPresets} from './AppQuickPresets'
import type {ServiceFormValues} from './serviceFormValues'
import type {RestartPolicy} from './serviceTypes'
import {restartPolicyHint, restartPolicyLabel} from './serviceVocabulary'

export function RuntimeFields({
  form,
  restartPolicies,
  disabled,
  showProbeInterval,
}: {
  form: FormFields<ServiceFormValues>
  restartPolicies: RestartPolicy[]
  disabled?: boolean
  /** The settings screen offers the probe interval; the new-service form does not. */
  showProbeInterval?: boolean
}) {
  const policy = form.data.restartPolicy as RestartPolicy
  const isBaseLinux = /^(ubuntu|debian|alpine|centos|archlinux|fedora)(:.*)?$/i.test(form.data.image.trim())
  const needsKeepAlive = isBaseLinux && !form.data.command.trim()

  return (
    <div className="flex flex-col gap-4">
      {!showProbeInterval ? (
        <AppQuickPresets form={form} disabled={disabled} />
      ) : null}

      <Input
        {...form.bind('image')}
        label={t('service.runtime.image_label')}
        required
        disabled={disabled}
        maxLength={500}
        autoComplete="off"
        placeholder={t('service.runtime.image_placeholder')}
        hint={t('service.runtime.image_hint')}
      />

      <div className="flex flex-col gap-1">
        <Input
          {...form.bind('command')}
          label={t('service.runtime.command_label')}
          disabled={disabled}
          maxLength={2000}
          autoComplete="off"
          placeholder={t('service.runtime.command_placeholder')}
          hint={t('service.runtime.command_hint')}
        />
        {needsKeepAlive ? (
          <div className="flex items-center justify-between rounded-lg border border-accent-500/30 bg-accent-500/10 px-3 py-2 text-xs">
            <span className="text-ink-700 dark:text-ink-300">
              {t('service.runtime.base_os_warning')}
            </span>
            <button
              type="button"
              disabled={disabled}
              onClick={() => form.set('command', 'sleep infinity')}
              className="font-medium text-accent-600 hover:underline dark:text-accent-400"
            >
              {t('service.runtime.apply_keepalive')}
            </button>
          </div>
        ) : null}
      </div>

      <Input
        {...form.bind('entrypoint')}
        label={t('service.runtime.entrypoint_label')}
        disabled={disabled}
        maxLength={2000}
        autoComplete="off"
        hint={t('service.runtime.entrypoint_hint')}
      />

      <Input
        {...form.bind('workingDir')}
        label={t('service.runtime.working_dir_label')}
        disabled={disabled}
        maxLength={500}
        autoComplete="off"
        placeholder={t('service.runtime.working_dir_placeholder')}
        hint={t('service.runtime.working_dir_hint')}
      />

      <Input
        {...form.bind('containerPort')}
        label={t('service.runtime.port_label')}
        type="number"
        inputMode="numeric"
        min={1}
        max={65535}
        disabled={disabled}
        hint={t('service.runtime.port_hint')}
      />

      <Input
        {...form.bind('healthCheckPath')}
        label={t('service.runtime.health_check_label')}
        disabled={disabled}
        maxLength={500}
        autoComplete="off"
        placeholder={t('service.runtime.health_check_placeholder')}
        hint={t('service.runtime.health_check_hint')}
      />

      {showProbeInterval ? (
        <Input
          {...form.bind('healthCheckIntervalSeconds')}
          label={t('service.runtime.probe_every_label')}
          type="number"
          inputMode="numeric"
          min={1}
          max={3600}
          disabled={disabled}
          suffix={t('service.runtime.seconds_suffix')}
          hint={t('service.runtime.probe_hint')}
        />
      ) : null}

      <Select
        {...form.bind('restartPolicy')}
        label={t('service.runtime.restart_label')}
        disabled={disabled}
        hint={restartPolicyHint(policy)}
        options={restartPolicies.map((value) => ({
          value,
          label: restartPolicyLabel(value),
        }))}
      />
    </div>
  )
}
