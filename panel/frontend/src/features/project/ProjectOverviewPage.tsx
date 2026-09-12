import {Head, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
import {Badge, ButtonLink, Card, Icon, PageHeader, mayWrite} from '@/shell'
import type {MemberRole} from '@/shell'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'
import {QUOTA_WARNING_SHARE, quotaShare} from '@/features/org/quotaVocabulary'
import {ServiceList} from '@/features/service/ServiceList'
import type {ServiceSummary} from '@/features/service/serviceTypes'

import {ProjectTabs} from './ProjectTabs'
import type {Project} from './projectTypes'

/**
 * `GET /projects/{projectId}` - one project and the services in it.
 *
 * `serviceAllowance` is here so the page can refuse before the form does. A customer who
 * fills in eleven fields and is then told the plan is full has wasted the two minutes it
 * took; a disabled button with the reason next to it costs nothing and says the same
 * thing. The server checks again regardless - this only decides what is drawn.
 */
type ProjectOverviewProps = {
  project: Project
  services: ServiceSummary[]
  viewerRole: MemberRole
  /** The SERVICE quota for the organization this project is in. */
  serviceAllowance: QuotaAllowance
}

export default function ProjectOverviewPage() {
  const {project, services, viewerRole, serviceAllowance} =
    usePage<ProjectOverviewProps>().props

  const writable = mayWrite(viewerRole) && !project.archived
  const full = serviceAllowance.used >= serviceAllowance.limit
  const pressing = quotaShare(serviceAllowance.used, serviceAllowance.limit) >= QUOTA_WARNING_SHARE
  const live = services.filter((service) => !service.archived)

  return (
    <div className="flex flex-col gap-4">
      <Head title={project.name} />
      <ProjectTabs projectId={project.id} />

      <PageHeader
        title={project.name}
        description={project.description || t('project.overview.fallbackDesc', {slug: project.slug})}
        actions={
          writable && !full ? (
            <ButtonLink href={`/projects/${project.id}/services/new`} icon={<Icon name="node" />}>
              {t('project.overview.addService')}
            </ButtonLink>
          ) : null
        }
      />

      {project.archived ? (
        <Card>
          <p className="text-sm text-ink-700 dark:text-ink-300">
            {t('project.overview.archivedNotice')}
          </p>
        </Card>
      ) : null}

      {pressing ? (
        <Card>
          <QuotaMeter allowance={serviceAllowance} className="py-0" />
        </Card>
      ) : null}

      <Card
        title={t('project.overview.servicesCardTitle')}
        description={t('project.overview.servicesCardDesc')}
        action={
          <Badge tone={live.length > 0 ? 'accent' : 'neutral'}>
            {serviceAllowance.used} / {serviceAllowance.limit}
          </Badge>
        }
        padded={false}
      >
        <ServiceList
          services={services}
          emptyAction={
            writable && !full ? (
              <ButtonLink href={`/projects/${project.id}/services/new`}>
                {t('project.overview.addFirstService')}
              </ButtonLink>
            ) : null
          }
        />
      </Card>
    </div>
  )
}
