import {Head, router, usePage} from '@inertiajs/react'

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

/**
 * `GET /services/{serviceId}/deployments/{deploymentId}` - one deployment, live.
 *
 * The log comes down twice by design: the first five hundred lines are rendered from the
 * table with the page, so a finished build is readable the instant it paints, and the SSE
 * stream then picks up from `logCursor` and sends only what is not already here. A
 * customer on a train whose connection drops reads one continuous log, because every line
 * carries its sequence and the browser resumes from the last one it got.
 *
 * The order down the page is what somebody wants on a phone in the two minutes they are
 * watching a deploy: what state is it in, then the output, then the path it took, then the
 * details they only want when comparing this deploy with another.
 */
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
      <Head title={`Deployment #${deployment.sequence} · ${service.name}`} />
      <ServiceTabs serviceId={service.serviceId} />

      <PageHeader
        title={`Deployment #${deployment.sequence}`}
        description={describe(deployment)}
        actions={
          <>
            <ButtonLink
              href={`/services/${service.serviceId}/deployments`}
              variant="secondary"
              icon={<Icon name="chevronLeft" className="size-4" />}
            >
              All deployments
            </ButtonLink>
            {writable && cancellable(deployment) ? (
              <Button
                variant="danger"
                onClick={() => void cancelDeployment(service.serviceId, deployment)}
              >
                Cancel this build
              </Button>
            ) : null}
            {writable && rollbackTarget(deployment) ? (
              <Button onClick={() => void rollbackRelease(service.serviceId, deployment)}>
                Roll back to this
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
              ? 'This release is what visitors are being served.'
              : deployment.status === 'SUCCEEDED'
                ? 'Published, and something newer has taken over since.'
                : running
                  ? 'Still moving. This page follows the build as it goes.'
                  : 'Nothing further will happen to this deployment.'}
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
          // The prop is the status this page was rendered with. The stream only ends when
          // the deployment reached a terminal state, so once it does, re-read the row
          // rather than leave a "Building" pill above a log that has stopped.
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
