import {Tabs} from '@/shell'
import type {TabItem} from '@/shell'

import type {ServiceCounts} from './serviceTypes'

/**
 * Every screen one service has, in the order somebody works through them.
 *
 * The strip is the same for an app and for a static site, which is deliberate: a site
 * genuinely has a volumes page and a terminal page, and both explain in a sentence why
 * they are empty for it. Hiding a tab teaches the customer the panel cannot do the thing;
 * showing it with an explanation teaches them why it does not apply here. The server takes
 * the same view - `VolumeController` renders a reason rather than a 404.
 *
 * The paths are the ones `docs/contracts/panel-http.md` gives to `service`, `deploy`,
 * `domain`, `files` and `stats`. Nothing here invents a URL.
 */
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
    {href: `/services/${serviceId}`, label: 'Overview'},
    {href: `/services/${serviceId}/deployments`, label: 'Deployments'},
    {
      href: `/services/${serviceId}/environment`,
      label: 'Environment',
      badge: badge(environment),
    },
    {
      href: `/services/${serviceId}/volumes`,
      label: 'Volumes',
      badge: badge(counts?.volumes ?? null),
    },
    {
      href: `/services/${serviceId}/tasks`,
      label: 'Scheduled',
      badge: badge(counts?.scheduledTasks ?? null),
    },
    {href: `/services/${serviceId}/domains`, label: 'Domains'},
    {href: `/services/${serviceId}/files`, label: 'Files'},
    {href: `/services/${serviceId}/terminal`, label: 'Terminal'},
    {href: `/services/${serviceId}/logs`, label: 'Logs'},
    {href: `/services/${serviceId}/metrics`, label: 'Metrics'},
    {href: `/services/${serviceId}/settings`, label: 'Settings'},
  ]

  return <Tabs label="Service sections" items={items} />
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
