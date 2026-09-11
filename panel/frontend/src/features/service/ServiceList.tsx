import type {ReactNode} from 'react'

import {DataList, EmptyState, Icon, RelativeTime} from '@/shell'

import {ServiceStatusBadge} from './ServiceStatusBadge'
import type {ServiceSummary} from './serviceTypes'
import {kindLabel, presetLabel} from './serviceVocabulary'

/**
 * The services inside one project.
 *
 * Lives here rather than in `features/project` because the row is made of `ServiceSummary`
 * and of this package's vocabulary; the project overview imports it. A second copy of this
 * list living next to the page that happens to render it is how the pill on the dashboard
 * and the pill on the service screen end up disagreeing about what "degraded" looks like.
 *
 * A table above `md`, one row per service below it - `DataList` chooses. There are no
 * swipe actions: everything a customer can do to a service needs the service's own screen,
 * and a swipe that only navigates is a swipe that hides the tap.
 */
export function ServiceList({
  services,
  emptyAction,
}: {
  services: ServiceSummary[]
  /** The "add a service" control, shown inside the empty state where it is the next step. */
  emptyAction?: ReactNode
}) {
  return (
    <DataList
      items={services}
      keyOf={(service) => service.id}
      label="services"
      href={(service) => `/services/${service.id}`}
      primary={(service) => service.name}
      secondary={(service) => describe(service)}
      trailing={(service) => <ServiceStatusBadge status={service} />}
      columns={[
        {key: 'name', header: 'Service', cell: (service) => service.name},
        {
          key: 'kind',
          header: 'Kind',
          cell: (service) => kindLabel(service.kind),
        },
        {
          key: 'source',
          header: 'Runs',
          cell: (service) => (
            <span className="font-mono text-xs break-all">{source(service)}</span>
          ),
        },
        {
          key: 'reported',
          header: 'Last reported',
          cell: (service) => <RelativeTime at={service.reportedAt} fallback="never" />,
        },
        {
          key: 'status',
          header: 'State',
          align: 'right',
          cell: (service) => <ServiceStatusBadge status={service} />,
        },
      ]}
      empty={
        <EmptyState
          icon={<Icon name="projects" />}
          title="No services yet"
          description="A service is either an app - a container the platform keeps running - or a
            static site built from a repository. Add the first one and it will show up here with
            whatever the node reports about it."
          action={emptyAction}
        />
      }
    />
  )
}

/** The phone row's second line: what it is, and what it runs. */
function describe(service: ServiceSummary): string {
  return `${kindLabel(service.kind)} · ${source(service)}`
}

function source(service: ServiceSummary): string {
  if (service.kind === 'SITE') {
    return service.buildPreset ? presetLabel(service.buildPreset) : 'no build preset'
  }
  return service.image ?? 'no image'
}
