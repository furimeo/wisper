import {useState} from 'react'

import {t} from '@/i18n'
import {Button, Card, Tabs, useFormFields} from '@/shell'
import type {TabItem} from '@/shell'

import type {QuotaAllowance} from '@/features/org/orgTypes'

import {DeployArchiveForm} from './DeployArchiveForm'
import {DeployFromGitForm} from './DeployFromGitForm'
import type {DeploymentTarget} from './deployTypes'

/**
 * The card that starts a deployment, in whichever of its three shapes this service has.
 *
 * A site with a repository can deploy from Git or from a zip, and both are offered
 * because they are genuinely alternatives - a customer mid-migration uploads a build while
 * the repository is still being sorted out. A site without a repository gets the zip and a
 * sentence saying where to add one. An app has neither: it deploys the image it is
 * configured with, so the whole card is one button.
 *
 * Which of the two forms is showing is local state rather than a query parameter. It is a
 * choice about this one action, it is not worth a round trip, and a customer who reloads
 * the page after a failed upload wants the page's own default rather than the mode they
 * were in twenty minutes ago.
 */
export function StartDeployPanel({
  target,
  allowance,
  writable,
}: {
  target: DeploymentTarget
  /** `DEPLOYMENTS_PER_DAY`. The button is greyed out with the reason, not silently refused. */
  allowance: QuotaAllowance
  writable: boolean
}) {
  const spent = allowance.limit > 0 && allowance.used >= allowance.limit
  const disabled = !writable || spent
  const reason = !writable
    ? t('deploy.start.reason_read_only')
    : spent
      ? t('deploy.start.reason_spent', {limit: allowance.limit})
      : undefined

  if (!target.deploysFromGit && !target.acceptsArchive) {
    return (
      <RedeployImageCard target={target} disabled={disabled} disabledReason={reason} />
    )
  }

  return (
    <SiteDeployCard target={target} disabled={disabled} disabledReason={reason} />
  )
}

/** An app: nothing to build and nothing to unpack, so one button and one sentence. */
function RedeployImageCard({
  target,
  disabled,
  disabledReason,
}: {
  target: DeploymentTarget
  disabled: boolean
  disabledReason: string | undefined
}) {
  const form = useFormFields({})

  return (
    <Card
      title={t('deploy.start.app_card_title')}
      description={t('deploy.start.app_card_desc')}
    >
      <Button
        block
        loading={form.processing}
        disabled={disabled}
        onClick={() => form.submit(`/services/${target.serviceId}/deployments`)}
      >
        {t('deploy.start.deploy_configured_image')}
      </Button>
      <p className="mt-3 text-sm text-ink-500 dark:text-ink-400">
        {disabledReason ?? t('deploy.start.app_hint_default')}
      </p>
    </Card>
  )
}

/** A site: Git, a zip, or both. */
function SiteDeployCard({
  target,
  disabled,
  disabledReason,
}: {
  target: DeploymentTarget
  disabled: boolean
  disabledReason: string | undefined
}) {
  const both = target.deploysFromGit && target.acceptsArchive
  const [mode, setMode] = useState<'git' | 'archive'>(
    target.deploysFromGit ? 'git' : 'archive',
  )

  const items: TabItem[] = [
    {value: 'git', label: t('deploy.start.tab_git')},
    {value: 'archive', label: t('deploy.start.tab_archive')},
  ]

  return (
    <Card
      title={t('deploy.start.site_card_title')}
      description={t('deploy.start.site_card_desc')}
    >
      {both ? (
        <Tabs
          label={t('deploy.start.tabs_label')}
          items={items}
          value={mode}
          onSelect={(value) => setMode(value === 'archive' ? 'archive' : 'git')}
          className="mb-4"
        />
      ) : null}

      {mode === 'git' && target.deploysFromGit ? (
        <DeployFromGitForm
          target={target}
          disabled={disabled}
          disabledReason={disabledReason}
        />
      ) : (
        <>
          <DeployArchiveForm
            target={target}
            disabled={disabled}
            disabledReason={disabledReason}
          />
          {target.deploysFromGit ? null : (
            <p className="mt-3 text-sm text-ink-500 dark:text-ink-400">
              {t('deploy.start.no_repo_hint')}
            </p>
          )}
        </>
      )}
    </Card>
  )
}
