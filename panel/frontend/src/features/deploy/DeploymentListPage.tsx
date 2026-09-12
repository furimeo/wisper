import {Head, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
import {Card, PageHeader, mayWrite} from '@/shell'
import type {MemberRole} from '@/shell'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'
import {ServiceTabs} from '@/features/service/ServiceTabs'

import {DeploymentHistory} from './DeploymentHistory'
import {ReleaseRetentionCard} from './ReleaseRetentionCard'
import {StartDeployPanel} from './StartDeployPanel'
import {WebhookCard} from './WebhookCard'
import type {DeploymentSummary, DeploymentTarget} from './deployTypes'

type DeploymentListProps = {
  service: DeploymentTarget
  deployments: DeploymentSummary[]
  viewerRole: MemberRole
  deploymentAllowance: QuotaAllowance
}

export default function DeploymentListPage() {
  const {service, deployments, viewerRole, deploymentAllowance} =
    usePage<DeploymentListProps>().props
  const writable = mayWrite(viewerRole)

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('deploy.list.head_title', {name: service.name})} />
      <ServiceTabs serviceId={service.serviceId} />

      <PageHeader
        title={t('deploy.list.title')}
        description={
          service.kind === 'SITE'
            ? t('deploy.list.description_site')
            : t('deploy.list.description_app')
        }
      />

      <StartDeployPanel
        target={service}
        allowance={deploymentAllowance}
        writable={writable}
      />

      {service.deploysFromGit ? <WebhookCard target={service} /> : null}

      <ReleaseRetentionCard target={service} deployments={deployments} />

      <Card title={t('deploy.list.history_card_title')} padded={false}>
        <DeploymentHistory
          serviceId={service.serviceId}
          deployments={deployments}
          writable={writable}
        />
      </Card>

      <Card title={t('deploy.list.today_card_title')}>
        <QuotaMeter allowance={deploymentAllowance} />
        <p className="text-sm text-ink-500 dark:text-ink-400">
          {t('deploy.list.today_hint')}
        </p>
      </Card>

      {writable ? null : (
        <p className="px-1 text-sm text-ink-500 dark:text-ink-400">
          {t('deploy.list.read_only_notice')}
        </p>
      )}
    </div>
  )
}
