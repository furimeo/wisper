import {t} from '@/i18n'
import {Tabs} from '@/shell'
import type {TabItem} from '@/shell'

import type {ServiceCounts} from './serviceTypes'

export function ServiceTabs({
  serviceId,
  counts,
}: {
  serviceId: string
  /** Badges on the three tabs whose contents somebody counts. Only the overview has them. */
  counts?: ServiceCounts
}) {
  const environment = counts ? counts.envVars + counts.secrets : null

  const items: TabItem[] = [
    {href: `/services/${serviceId}`, label: t('service.tabs.overview')},
    {href: `/services/${serviceId}/deployments`, label: t('service.tabs.deployments')},
    {
      href: `/services/${serviceId}/environment`,
      label: t('service.tabs.environment'),
      badge: badge(environment),
    },
    {
      href: `/services/${serviceId}/volumes`,
      label: t('service.tabs.volumes'),
      badge: badge(counts?.volumes ?? null),
    },
    {
      href: `/services/${serviceId}/tasks`,
      label: t('service.tabs.scheduled'),
      badge: badge(counts?.scheduledTasks ?? null),
    },
    {href: `/services/${serviceId}/domains`, label: t('service.tabs.domains')},
    {href: `/services/${serviceId}/files`, label: t('service.tabs.files')},
    {href: `/services/${serviceId}/terminal`, label: t('service.tabs.terminal')},
    {href: `/services/${serviceId}/logs`, label: t('service.tabs.logs')},
    {href: `/services/${serviceId}/metrics`, label: t('service.tabs.metrics')},
    {href: `/services/${serviceId}/settings`, label: t('service.tabs.settings')},
  ]

  return <Tabs label={t('service.tabs.sections_label')} items={items} />
}

/** A count, or nothing at all. A badge reading "0" is a badge worth less than the space. */
function badge(count: number | null) {
  if (count === null || count === 0) {
    return undefined
  }
  return (
    <span className="rounded-full bg-ink-200 px-1.5 text-xs tabular-nums text-ink-700 dark:bg-ink-700 dark:text-ink-200">
      {count}
    </span>
  )
}
