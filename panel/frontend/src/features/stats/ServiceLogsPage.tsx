import {Head, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
import {Badge, ButtonLink, Card, PageHeader} from '@/shell'

import {ServiceTabs} from '@/features/service/ServiceTabs'
import type {ServiceLocation} from '@/features/service/serviceTypes'

import {LogConsole} from './LogConsole'
import type {LogSourceName} from './statsTypes'
import {useLogStream} from './useLogStream'

/**
 * `GET /services/{serviceId}/logs` - what the application is printing, as it prints it.
 *
 * A live tail with a screenful of history, and nothing else, because there is nothing
 * else: container output lives on the node in Docker's rotated files and is never copied
 * into the panel's database. That is a deliberate limit rather than a missing feature -
 * a platform that stores every tenant's stdout becomes a disk-space problem long before
 * it becomes a search product - and the page says so instead of implying a scrollback that
 * is not there.
 *
 * Two sources reach this screen. The container's own output is the default and is what
 * somebody who followed the tab means. A cron run is the other, linked from the scheduled
 * tasks page with the run's id; its output is finite, so that stream ends on its own and
 * the viewer says so rather than sitting on a spinner.
 */
type ServiceLogsProps = {
  service: ServiceLocation
  source: LogSourceName
  subjectId: string
  tailLines: number
  placed: boolean
}

export default function ServiceLogsPage() {
  const {service, source, subjectId, tailLines, placed} = usePage<ServiceLogsProps>().props
  const cron = source === 'LOG_SOURCE_CRON'

  const stream = useLogStream(service.serviceId, source, subjectId, tailLines, placed)

  const isSite = service.kind === 'SITE'

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('stats.serviceLogs.title', {name: service.name})} />
      <ServiceTabs serviceId={service.serviceId} kind={service.kind} />

      <PageHeader
        title={t('stats.serviceLogs.header')}
        description={
          isSite
            ? t('stats.serviceLogs.emptyDescSite', {name: service.name})
            : cron
              ? t('stats.serviceLogs.descCron')
              : t('stats.serviceLogs.descContainer', {name: service.name, tailLines})
        }
        actions={
          isSite ? (
            <ButtonLink href={`/services/${service.serviceId}/deployments`} variant="primary">
              {t('stats.serviceLogs.deployments')}
            </ButtonLink>
          ) : cron ? (
            <ButtonLink href={`/services/${service.serviceId}/logs`} variant="secondary">
              {t('stats.serviceLogs.actionContainer')}
            </ButtonLink>
          ) : null
        }
      />

      {cron ? (
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone="accent">{t('stats.serviceLogs.scheduledRunBadge')}</Badge>
          <span className="font-mono text-xs text-ink-500 dark:text-ink-400">{subjectId}</span>
        </div>
      ) : null}

      {isSite ? (
        <Card title={t('stats.serviceLogs.emptyTitleSite')}>
          <p className="text-sm text-ink-700 dark:text-ink-300">
            {t('stats.serviceLogs.emptyDescSite', {name: service.name})}
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <ButtonLink href={`/services/${service.serviceId}`} variant="secondary">
              {t('stats.serviceLogs.goToService')}
            </ButtonLink>
            <ButtonLink href={`/services/${service.serviceId}/deployments`} variant="secondary">
              {t('stats.serviceLogs.deployments')}
            </ButtonLink>
          </div>
        </Card>
      ) : placed ? (
        <LogConsole stream={stream} placed={placed} />
      ) : (
        <Card title={t('stats.serviceLogs.emptyTitle')}>
          <p className="text-sm text-ink-700 dark:text-ink-300">
            {t('stats.serviceLogs.emptyDesc', {name: service.name})}
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <ButtonLink href={`/services/${service.serviceId}`} variant="secondary">
              {t('stats.serviceLogs.goToService')}
            </ButtonLink>
            <ButtonLink href={`/services/${service.serviceId}/deployments`} variant="secondary">
              {t('stats.serviceLogs.deployments')}
            </ButtonLink>
          </div>
        </Card>
      )}

      <p className="px-1 text-xs text-ink-500 dark:text-ink-400">
        {t('stats.serviceLogs.footer')}
      </p>
    </div>
  )
}
