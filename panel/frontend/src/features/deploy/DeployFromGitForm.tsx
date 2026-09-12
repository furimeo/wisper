import {t} from '@/i18n'
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
        label={t('deploy.git_form.ref_label')}
        placeholder={branch}
        autoCapitalize="none"
        autoCorrect="off"
        spellCheck={false}
        enterKeyHint="go"
        disabled={disabled}
        hint={t('deploy.git_form.ref_hint', {branch})}
      />

      <Button type="submit" block loading={form.processing} disabled={disabled}>
        {t('deploy.git_form.deploy_now')}
      </Button>

      {disabled && disabledReason ? (
        <p className="text-sm text-ink-500 dark:text-ink-400">{disabledReason}</p>
      ) : (
        <p className="text-sm text-ink-500 dark:text-ink-400">
          {t('deploy.git_form.explanation', {url: target.repositoryUrl ?? ''})}
        </p>
      )}
    </form>
  )
}
