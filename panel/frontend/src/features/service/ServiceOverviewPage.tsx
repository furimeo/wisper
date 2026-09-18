import {Head, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
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

type ServiceOverviewProps = {
  service: ServiceView
  status: ServiceSummary | null
  project: Project
  viewerRole: MemberRole
  counts: ServiceCounts
  primaryHostname?: string | null
}

export default function ServiceOverviewPage() {
  const {service, status, project, viewerRole, counts, primaryHostname} =
    usePage<ServiceOverviewProps>().props
  const writable = mayWrite(viewerRole)

  return (
    <div className="flex flex-col gap-4">
      <Head title={service.name} />
      <ServiceTabs serviceId={service.id} kind={service.kind} counts={counts} />

      <PageHeader
        title={service.name}
        description={t('service.overview.description', {
          kind: kindLabel(service.kind),
          project: project.name,
          slug: service.slug,
        })}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            {primaryHostname ? (
              <ButtonLink
                href={`https://${primaryHostname}`}
                target="_blank"
                rel="noreferrer"
                variant="primary"
                icon={<Icon name="external" />}
              >
                {t('service.overview.open_website')}
              </ButtonLink>
            ) : null}
            {service.app ? (
              <ButtonLink
                href={`/services/${service.id}/terminal`}
                variant="secondary"
                icon={<Icon name="monitor" />}
              >
                {t('service.overview.terminal_button')}
              </ButtonLink>
            ) : null}
            <ButtonLink
              href={`/services/${service.id}/files`}
              variant="secondary"
              icon={<Icon name="projects" />}
            >
              {t('service.overview.files_button')}
            </ButtonLink>
            <ButtonLink
              href={`/services/${service.id}/deployments`}
              variant="secondary"
              icon={<Icon name="jobs" />}
            >
              {t('service.overview.deployments_button')}
            </ButtonLink>
          </div>
        }
      />

      {service.archived ? (
        <Card>
          <p className="text-sm text-ink-700 dark:text-ink-300">
            {t('service.overview.archived_notice')}
          </p>
        </Card>
      ) : null}

      <IsolationWarning service={service} />

      {service.app && service.command?.includes('sleep infinity') ? (
        <Card title={t('service.overview.bot_mode_title')}>
          <div className="flex flex-col gap-3">
            <p className="text-sm text-ink-700 dark:text-ink-300">
              {t('service.overview.bot_mode_desc')}
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <ButtonLink
                href={`/services/${service.id}/files`}
                variant="secondary"
                size="sm"
                icon={<Icon name="projects" />}
              >
                {t('service.overview.bot_mode_files_btn')}
              </ButtonLink>
              <ButtonLink
                href={`/services/${service.id}/terminal`}
                variant="secondary"
                size="sm"
                icon={<Icon name="monitor" />}
              >
                {t('service.overview.bot_mode_terminal_btn')}
              </ButtonLink>
              <ButtonLink
                href={`/services/${service.id}/settings`}
                variant="secondary"
                size="sm"
                icon={<Icon name="settings" />}
              >
                {t('service.overview.bot_mode_settings_btn')}
              </ButtonLink>
            </div>
          </div>
        </Card>
      ) : null}

      <ServiceStatePanel service={service} status={status} writable={writable} />

      <ServiceFacts service={service} />

      <ServiceShortcuts service={service} counts={counts} />

      {writable ? null : (
        <p className="px-1 text-sm text-ink-500 dark:text-ink-400">
          {t('service.overview.read_only_notice')}
        </p>
      )}
    </div>
  )
}
