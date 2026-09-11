import {Head, usePage} from '@inertiajs/react'

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

/**
 * `GET /services/{serviceId}/deployments` - what this service has deployed, and how to
 * deploy it again.
 *
 * The order is what somebody wants in the two situations they open this page in. Arriving
 * to ship a change, the deploy card is the first thing under the header. Arriving because
 * the site is broken, the history is one scroll away and the newest row carries the state
 * and a rollback action. Neither reading needs a tab or a menu.
 *
 * `deploymentAllowance` is passed so the button can be greyed out with the reason on it
 * rather than accepting the tap and answering with a refusal - the server checks the quota
 * again either way, but being told before is the difference between a limit and a
 * surprise.
 */
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
      <Head title={`Deployments · ${service.name}`} />
      <ServiceTabs serviceId={service.serviceId} />

      <PageHeader
        title="Deployments"
        description={
          service.kind === 'SITE'
            ? 'Every build of this site, newest first. Publishing is a symlink swap, so going live and going back are both instant.'
            : 'Every deployment of this app, newest first. Each one replaces the container with a fresh one from the image it names.'
        }
      />

      <StartDeployPanel
        target={service}
        allowance={deploymentAllowance}
        writable={writable}
      />

      {service.deploysFromGit ? <WebhookCard target={service} /> : null}

      <ReleaseRetentionCard target={service} deployments={deployments} />

      <Card title="History" padded={false}>
        <DeploymentHistory
          serviceId={service.serviceId}
          deployments={deployments}
          writable={writable}
        />
      </Card>

      <Card title="Today's deployments">
        <QuotaMeter allowance={deploymentAllowance} />
        <p className="text-sm text-ink-500 dark:text-ink-400">
          Counted per organization across every service, and it is a rolling
          twenty-four hours rather than a calendar day. A rollback counts too: it is a
          deployment, it just does not build anything.
        </p>
      </Card>

      {writable ? null : (
        <p className="px-1 text-sm text-ink-500 dark:text-ink-400">
          You have read access to this organization, so deploying, cancelling and rolling
          back are off. The history and every build log are still open to you.
        </p>
      )}
    </div>
  )
}
