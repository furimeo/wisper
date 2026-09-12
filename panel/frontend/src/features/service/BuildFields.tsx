import {t} from '@/i18n'
import {Input, Select} from '@/shell'
import type {FormFields} from '@/shell'

import type {ServiceFormValues} from './serviceFormValues'
import type {BuildPreset} from './serviceTypes'
import {presetLabel, presetNeedsCommand, presetOutputDir} from './serviceVocabulary'

export function BuildFields({
  form,
  presets,
  required,
  disabled,
}: {
  form: FormFields<ServiceFormValues>
  presets: BuildPreset[]
  required?: boolean
  disabled?: boolean
}) {
  const preset = form.data.buildPreset as BuildPreset | ''
  const custom = preset !== '' && presetNeedsCommand(preset)

  return (
    <div className="flex flex-col gap-4">
      <Select
        {...form.bind('buildPreset')}
        label={t('service.build.build_label')}
        required={required}
        disabled={disabled}
        placeholder={required ? t('service.build.choose_preset_placeholder') : undefined}
        onChange={(event) => {
          const chosen = event.target.value as BuildPreset | ''
          const suggested = chosen === '' ? '' : presetOutputDir(chosen)
          const untouched =
            form.data.buildOutputDir === '' ||
            (preset !== '' && form.data.buildOutputDir === presetOutputDir(preset))
          form.patch(
            untouched
              ? {buildPreset: chosen, buildOutputDir: suggested}
              : {buildPreset: chosen},
          )
        }}
        options={[
          ...(required ? [] : [{value: '', label: t('service.build.no_build_option')}]),
          ...presets.map((value) => ({value, label: presetLabel(value)})),
        ]}
      />

      {preset === '' ? null : (
        <>
          <Input
            {...form.bind('buildCommand')}
            label={t('service.build.command_label')}
            required={custom}
            disabled={disabled}
            maxLength={2000}
            autoComplete="off"
            placeholder={
              custom
                ? t('service.build.command_placeholder_custom')
                : t('service.build.command_placeholder_preset')
            }
            hint={
              custom
                ? t('service.build.command_hint_custom')
                : t('service.build.command_hint_preset')
            }
          />

          <Input
            {...form.bind('buildOutputDir')}
            label={t('service.build.output_dir_label')}
            required={required}
            disabled={disabled}
            maxLength={500}
            autoComplete="off"
            placeholder={t('service.build.output_dir_placeholder')}
            hint={t('service.build.output_dir_hint')}
          />

          <Input
            {...form.bind('keepReleases')}
            label={t('service.build.keep_releases_label')}
            type="number"
            inputMode="numeric"
            min={1}
            max={50}
            disabled={disabled}
            hint={t('service.build.keep_releases_hint')}
          />
        </>
      )}
    </div>
  )
}
