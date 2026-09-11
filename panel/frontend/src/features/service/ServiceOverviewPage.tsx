import {Head, usePage} from '@inertiajs/react'

import {ButtonLink, Card, Icon, PageHeader, mayWrite} from '@/shell'
import type {MemberRole} from '@/shell'

import type {Project} from '@/features/project/projectTypes'

import {IsolationWarning} from './IsolationWarning'
import {ServiceFacts} from './ServiceFacts'
import {ServiceShortcuts} from './ServiceShortcuts'
import {ServiceStatePanel} from './ServiceStatePanel'
import {ServiceTabs} from './ServiceTabs'
import type {ServiceCounts, ServiceSummary, ServiceView} from './serviceTypes'
import {kindLabel} from './serviceVocabulary'

/**
 * `GET /services/{serviceId}` - one service: what it is doing, what it is made of, and
 * the way to everything else about it.
 *
 * `status` is null before a node has reported, and that is a real state rather than a
 * missing value: the page says so in words instead of drawing an empty pill. Intent and
 * fact arrive as two props on purpose - a service the customer asked to run and the node
 * says has crashed is the interesting case, and one prop could not show it.
 *
 * The order down the page is the order somebody wants it on a phone: is it up, what is
 * wrong with it, what is it, where do I go next. The state panel is first because it is
 * both the answer and the controls.
 */
type ServiceOverviewProps = {
  service: ServiceView
  status: ServiceSummary | null
  project: Project
  viewerRole: MemberRole
  counts: ServiceCounts
}

export default function ServiceOverviewPage() {
  const {service, status, project, viewerRole, counts} = usePage<ServiceOverviewProps>().props
  const writable = mayWrite(viewerRole)

  return (
    <div className="flex flex-col gap-4">
      <Head title={service.name} />
      <ServiceTabs serviceId={service.id} counts={counts} />

      <PageHeader
        title={service.name}
        description={`${kindLabel(service.kind)} in ${project.name}, at /${service.slug}.`}
        actions={
          <ButtonLink
            href={`/services/${service.id}/deployments`}
            variant="secondary"
            icon={<Icon name="jobs" />}
          >
            Deployments
          </ButtonLink>
        }
      />

      {service.archived ? (
        <Card>
          <p className="text-sm text-ink-700 dark:text-ink-300">
            This service is archived, which is what happens to everything in a project that
            gets archived. Nothing is running and nothing was deleted - restore the project
            from its settings and this comes back stopped.
          </p>
        </Card>
      ) : null}

      <IsolationWarning service={service} />

      <ServiceStatePanel service={service} status={status} writable={writable} />

      <ServiceFacts service={service} />

      <ServiceShortcuts service={service} counts={counts} />

      {writable ? null : (
        <p className="px-1 text-sm text-ink-500 dark:text-ink-400">
          You have read access to this organization, so the controls above are off. Logs and
          metrics are still open to you.
        </p>
      )}
    </div>
  )
}
