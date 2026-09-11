import {Button, Input, useFormFields} from '@/shell'

import type {DeploymentTarget} from './deployTypes'

/**
 * `POST /services/{serviceId}/deployments` with an optional `ref`.
 *
 * The branch box is optional and pre-filled with nothing rather than with the service's
 * branch: an empty field posts nothing, the controller falls back to the configured
 * branch, and the placeholder says which one that is. Pre-filling it would look identical
 * and would mean a customer who cleared the box got `main` instead of their own default.
 *
 * The button is full width and sits under the field, which on a 375px screen is the only
 * arrangement where both are reachable with one thumb. `processing` disables it for the
 * length of the request, because a double tap on a slow connection queues two builds of
 * the same commit and burns a node's minutes twice.
 */
export function DeployFromGitForm({
  target,
  disabled,
  disabledReason,
}: {
  target: DeploymentTarget
  disabled: boolean
  /** Why the button is off - a quota that is spent, or a read-only membership. */
  disabledReason?: string
}) {
  const form = useFormFields({ref: ''})
  const branch = target.repositoryBranch ?? 'main'

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        form.submit(`/services/${target.serviceId}/deployments`)
      }}
      className="flex flex-col gap-3"
    >
      <Input
        {...form.bind('ref')}
        label="Branch, tag or commit"
        placeholder={branch}
        autoCapitalize="none"
        autoCorrect="off"
        spellCheck={false}
        enterKeyHint="go"
        disabled={disabled}
        hint={`Leave this empty to build ${branch}, the branch this service is set to.`}
      />

      <Button type="submit" block loading={form.processing} disabled={disabled}>
        Deploy now
      </Button>

      {disabled && disabledReason ? (
        <p className="text-sm text-ink-500 dark:text-ink-400">{disabledReason}</p>
      ) : (
        <p className="text-sm text-ink-500 dark:text-ink-400">
          wisper clones {target.repositoryUrl}, builds it on the node holding this service
          and swaps the release directory over when the build succeeds. Nothing goes down
          while it runs.
        </p>
      )}
    </form>
  )
}
