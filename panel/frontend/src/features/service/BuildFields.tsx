import {Input, Select} from '@/shell'
import type {FormFields} from '@/shell'

import type {ServiceFormValues} from './serviceFormValues'
import type {BuildPreset} from './serviceTypes'
import {presetLabel, presetNeedsCommand, presetOutputDir} from './serviceVocabulary'

/**
 * How a repository becomes something to serve.
 *
 * Required for a static site, optional for an app - that is how a language-runtime image
 * gets the customer's code into it - so the group takes `required` rather than being two
 * components that would drift apart.
 *
 * Choosing a preset fills the output directory in with the one that preset usually
 * produces, and only while the box has not been touched. Overwriting something the
 * customer typed because they changed a dropdown is the kind of help nobody asked for.
 */
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
        label="Build"
        required={required}
        disabled={disabled}
        placeholder={required ? 'Choose how the site is built' : undefined}
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
          // An app may drop its build again; a site may not, so a site gets the disabled
          // placeholder above and no way back to "nothing".
          ...(required ? [] : [{value: '', label: 'No build - the image is the app'}]),
          ...presets.map((value) => ({value, label: presetLabel(value)})),
        ]}
      />

      {preset === '' ? null : (
        <>
          <Input
            {...form.bind('buildCommand')}
            label="Build command"
            required={custom}
            disabled={disabled}
            maxLength={2000}
            autoComplete="off"
            placeholder={custom ? 'npm ci && npm run build' : 'Leave empty for the preset default'}
            hint={
              custom
                ? 'A custom build needs the command that produces the site.'
                : 'Optional. The preset already knows how to build this.'
            }
          />

          <Input
            {...form.bind('buildOutputDir')}
            label="Output directory"
            required={required}
            disabled={disabled}
            maxLength={500}
            autoComplete="off"
            placeholder="dist"
            hint="Relative to the repository. It cannot start with a slash or contain
              &quot;..&quot; - the node resolves it under the build's own tree."
          />

          <Input
            {...form.bind('keepReleases')}
            label="Releases kept"
            type="number"
            inputMode="numeric"
            min={1}
            max={50}
            disabled={disabled}
            hint="Older builds stay on the node so a rollback is a symlink swap rather than a
              rebuild. Between 1 and 50."
          />
        </>
      )}
    </div>
  )
}
