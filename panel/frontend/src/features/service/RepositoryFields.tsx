import {Checkbox, Input} from '@/shell'
import type {FormFields} from '@/shell'

import type {ServiceFormValues} from './serviceFormValues'

/**
 * Where the code comes from, and whether a push deploys it.
 *
 * The credential box is the only input in the panel where empty does not mean empty. On
 * the settings screen it renders blank even when a deploy key is stored, because showing
 * one back is not something an encrypted column should make easy - so the hint has to say
 * outright that leaving it alone keeps what is there. `hasCredential` is what lets it say
 * which of the two situations the customer is in.
 */
export function RepositoryFields({
  form,
  hasCredential,
  disabled,
}: {
  form: FormFields<ServiceFormValues>
  /** True when a credential is already stored, from `ServiceView.hasRepositoryCredential`. */
  hasCredential?: boolean
  disabled?: boolean
}) {
  return (
    <div className="flex flex-col gap-4">
      <Input
        {...form.bind('repositoryUrl')}
        label="Repository"
        disabled={disabled}
        maxLength={1000}
        autoComplete="off"
        inputMode="url"
        placeholder="https://github.com/acme/storefront.git"
        hint="An https:// or ssh:// URL, or the git@host:owner/repo form."
      />

      <Input
        {...form.bind('repositoryBranch')}
        label="Branch"
        disabled={disabled}
        maxLength={250}
        autoComplete="off"
        placeholder="main"
        hint="Which branch a deployment builds from."
      />

      <Input
        {...form.bind('repositoryCredential')}
        label="Deploy key or token"
        type="password"
        disabled={disabled}
        maxLength={4000}
        autoComplete="new-password"
        hint={
          hasCredential
            ? 'One is stored. Leave this empty to keep it, or paste a new one to replace it.'
            : 'Only needed for a private repository. It is encrypted before it is stored.'
        }
      />

      <Checkbox
        {...form.check('autoDeploy')}
        disabled={disabled}
        label="Deploy automatically on a push"
        hint="The webhook URL is on the deployments screen once the service exists."
      />
    </div>
  )
}
