import {Head, usePage} from '@inertiajs/react'

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

  return (
    <div className="flex flex-col gap-4">
      <Head title={`Logs · ${service.name}`} />
      <ServiceTabs serviceId={service.serviceId} />

      <PageHeader
        title="Logs"
        description={
          cron
            ? 'One scheduled run, from the node that executed it.'
            : `Everything ${service.name} writes to stdout and stderr, tailed from the node holding it. The last ${tailLines} lines arrive first.`
        }
        actions={
          cron ? (
            <ButtonLink href={`/services/${service.serviceId}/logs`} variant="secondary">
              Container output
            </ButtonLink>
          ) : null
        }
      />

      {cron ? (
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone="accent">Scheduled run</Badge>
          <span className="font-mono text-xs text-ink-500 dark:text-ink-400">{subjectId}</span>
        </div>
      ) : null}

      {placed ? (
        <LogConsole stream={stream} placed={placed} />
      ) : (
        <Card title="Nothing is running this service">
          <p className="text-sm text-ink-700 dark:text-ink-300">
            Logs are read from the container, so there has to be one. Start {service.name} and
            this page will follow its output from the first line - the panel does not keep a
            copy, so there is nothing here from before it was placed on a node.
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <ButtonLink href={`/services/${service.serviceId}`} variant="secondary">
              Go to the service
            </ButtonLink>
            <ButtonLink href={`/services/${service.serviceId}/deployments`} variant="secondary">
              Deployments
            </ButtonLink>
          </div>
        </Card>
      )}

      <p className="px-1 text-xs text-ink-500 dark:text-ink-400">
        This is a tail, not an archive. The panel keeps no copy of container output, so what
        is on screen is what the node still holds - build logs are the exception and live on
        their deployment.
      </p>
    </div>
  )
}
