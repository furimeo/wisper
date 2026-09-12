import type {ReactNode} from 'react'

import {t} from '@/i18n'
import {DataList, EmptyState, Icon, RelativeTime} from '@/shell'

import {ServiceStatusBadge} from './ServiceStatusBadge'
import type {ServiceSummary} from './serviceTypes'
import {kindLabel, presetLabel} from './serviceVocabulary'

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
      label={t('service.list.label')}
      href={(service) => `/services/${service.id}`}
      primary={(service) => service.name}
      secondary={(service) => describe(service)}
      trailing={(service) => <ServiceStatusBadge status={service} />}
      columns={[
        {key: 'name', header: t('service.list.col_service'), cell: (service) => service.name},
        {
          key: 'kind',
          header: t('service.list.col_kind'),
          cell: (service) => kindLabel(service.kind),
        },
        {
          key: 'source',
          header: t('service.list.col_runs'),
          cell: (service) => (
            <span className="font-mono text-xs break-all">{source(service)}</span>
          ),
        },
        {
          key: 'reported',
          header: t('service.list.col_last_reported'),
          cell: (service) => <RelativeTime at={service.reportedAt} fallback={t('service.list.never')} />,
        },
        {
          key: 'status',
          header: t('service.list.col_state'),
          align: 'right',
          cell: (service) => <ServiceStatusBadge status={service} />,
        },
      ]}
      empty={
        <EmptyState
          icon={<Icon name="projects" />}
          title={t('service.list.no_services_title')}
          description={t('service.list.no_services_description')}
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
    return service.buildPreset ? presetLabel(service.buildPreset) : t('service.list.no_build_preset')
  }
  return service.image ?? t('service.list.no_image')
}
