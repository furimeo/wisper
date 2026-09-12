import {t} from '@/i18n'
import {Checkbox, Input} from '@/shell'
import type {FormFields} from '@/shell'

import type {ServiceFormValues} from './serviceFormValues'

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
        label={t('service.repository.repo_label')}
        disabled={disabled}
        maxLength={1000}
        autoComplete="off"
        inputMode="url"
        placeholder={t('service.repository.repo_placeholder')}
        hint={t('service.repository.repo_hint')}
      />

      <Input
        {...form.bind('repositoryBranch')}
        label={t('service.repository.branch_label')}
        disabled={disabled}
        maxLength={250}
        autoComplete="off"
        placeholder={t('service.repository.branch_placeholder')}
        hint={t('service.repository.branch_hint')}
      />

      <Input
        {...form.bind('repositoryCredential')}
        label={t('service.repository.credential_label')}
        type="password"
        disabled={disabled}
        maxLength={4000}
        autoComplete="new-password"
        hint={
          hasCredential
            ? t('service.repository.credential_stored_hint')
            : t('service.repository.credential_empty_hint')
        }
      />

      <Checkbox
        {...form.check('autoDeploy')}
        disabled={disabled}
        label={t('service.repository.auto_deploy_label')}
        hint={t('service.repository.auto_deploy_hint')}
      />
    </div>
  )
}
