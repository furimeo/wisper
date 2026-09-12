import {Head, router, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
import {Button, ButtonLink, Card, Icon, PageHeader, mayWrite} from '@/shell'
import type {MemberRole} from '@/shell'

import {ServiceTabs} from '@/features/service/ServiceTabs'

import {BuildLogViewer} from './BuildLogViewer'
import {DeploymentFacts} from './DeploymentFacts'
import {DeploymentStatusBadge} from './DeploymentStatusBadge'
import {DeploymentTimeline} from './DeploymentTimeline'
import {cancelDeployment} from './cancelDeployment'
import type {DeploymentLog, DeploymentSummary, DeploymentTarget} from './deployTypes'
import {cancellable, describe, inFlight, rollbackTarget} from './deployVocabulary'
import {rollbackRelease} from './rollbackRelease'

type DeploymentDetailProps = {
  service: DeploymentTarget
  deployment: DeploymentSummary
  log: DeploymentLog[]
  logCursor: number
  viewerRole: MemberRole
}

export default function DeploymentDetailPage() {
  const {service, deployment, log, logCursor, viewerRole} =
    usePage<DeploymentDetailProps>().props
  const writable = mayWrite(viewerRole)
  const running = inFlight(deployment)

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('deploy.detail.head_title', {sequence: deployment.sequence, name: service.name})} />
      <ServiceTabs serviceId={service.serviceId} />

      <PageHeader
        title={t('deploy.detail.title', {sequence: deployment.sequence})}
        description={describe(deployment)}
        actions={
          <>
            <ButtonLink
              href={`/services/${service.serviceId}/deployments`}
              variant="secondary"
              icon={<Icon name="chevronLeft" className="size-4" />}
            >
              {t('deploy.detail.all_deployments')}
            </ButtonLink>
            {writable && cancellable(deployment) ? (
              <Button
                variant="danger"
                onClick={() => void cancelDeployment(service.serviceId, deployment)}
              >
                {t('deploy.detail.cancel_build')}
              </Button>
            ) : null}
            {writable && rollbackTarget(deployment) ? (
              <Button onClick={() => void rollbackRelease(service.serviceId, deployment)}>
                {t('deploy.detail.rollback_to_this')}
              </Button>
            ) : null}
          </>
        }
      />

      <Card>
        <div className="flex flex-wrap items-center gap-3">
          <DeploymentStatusBadge deployment={deployment} />
          <span className="text-sm text-ink-600 dark:text-ink-400">
            {deployment.current
              ? t('deploy.detail.status_banner_current')
              : deployment.status === 'SUCCEEDED'
                ? t('deploy.detail.status_banner_succeeded')
                : running
                  ? t('deploy.detail.status_banner_running')
                  : t('deploy.detail.status_banner_terminal')}
          </span>
        </div>

        {deployment.errorMessage ? (
          <p
            role="alert"
            className="mt-3 rounded-lg border border-failed/40 bg-failed/10 px-3 py-2.5 text-sm leading-relaxed text-ink-900 dark:text-ink-100"
          >
            {deployment.errorMessage}
          </p>
        ) : null}
      </Card>

      <BuildLogViewer
        key={deployment.id}
        serviceId={service.serviceId}
        deploymentId={deployment.id}
        initialLines={log}
        cursor={logCursor}
        onEnded={() => {
          if (running) {
            router.reload({only: ['deployment']})
          }
        }}
      />

      <DeploymentTimeline deployment={deployment} />

      <DeploymentFacts deployment={deployment} target={service} />
    </div>
  )
}
