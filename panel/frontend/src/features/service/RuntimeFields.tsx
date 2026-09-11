import {Input, Select} from '@/shell'
import type {FormFields} from '@/shell'

import type {ServiceFormValues} from './serviceFormValues'
import type {RestartPolicy} from './serviceTypes'
import {restartPolicyHint, restartPolicyLabel} from './serviceVocabulary'

/**
 * What an app runs: the image, the argv, and how hard the node tries to keep it alive.
 *
 * Only an app gets these. A static site has no process, and `ServiceShape` refuses an
 * image, a port, a command and a health check on one - so the fields are absent rather
 * than present and rejected. A form that offers a box the server always says no to is a
 * form that teaches people to distrust it.
 *
 * `command` and `entrypoint` are one line each and are split into argv by `CommandLine` on
 * the server. Nothing here builds a shell string; the quoting rules are the ones a person
 * already knows from a terminal.
 */
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

  return (
    <div className="flex flex-col gap-4">
      <Input
        {...form.bind('image')}
        label="Container image"
        required
        disabled={disabled}
        maxLength={500}
        autoComplete="off"
        placeholder="ghcr.io/acme/api:1.4"
        hint="Including the tag. A digest is recorded once the node has pulled it."
      />

      <Input
        {...form.bind('command')}
        label="Command"
        disabled={disabled}
        maxLength={2000}
        autoComplete="off"
        placeholder="node server.js --port 8080"
        hint="Optional. Overrides the image's CMD. Quote an argument that contains spaces."
      />

      <Input
        {...form.bind('entrypoint')}
        label="Entrypoint"
        disabled={disabled}
        maxLength={2000}
        autoComplete="off"
        hint="Optional. Leave empty unless the image's own entrypoint gets in the way."
      />

      <Input
        {...form.bind('workingDir')}
        label="Working directory"
        disabled={disabled}
        maxLength={500}
        autoComplete="off"
        placeholder="/app"
        hint="Optional. A path inside the container, so it starts with a slash."
      />

      <Input
        {...form.bind('containerPort')}
        label="Port"
        type="number"
        inputMode="numeric"
        min={1}
        max={65535}
        disabled={disabled}
        hint="The port the app listens on inside the container. Needed before a domain can
          reach it, and before a health check has anything to probe."
      />

      <Input
        {...form.bind('healthCheckPath')}
        label="Health check path"
        disabled={disabled}
        maxLength={500}
        autoComplete="off"
        placeholder="/healthz"
        hint="Optional, and it needs the port above. A 2xx means healthy."
      />

      {showProbeInterval ? (
        <Input
          {...form.bind('healthCheckIntervalSeconds')}
          label="Probe every"
          type="number"
          inputMode="numeric"
          min={1}
          max={3600}
          disabled={disabled}
          suffix="seconds"
          hint="Between every second and every hour."
        />
      ) : null}

      <Select
        {...form.bind('restartPolicy')}
        label="Restart"
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
