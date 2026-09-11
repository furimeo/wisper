import {Link} from '@inertiajs/react'

import {Card, Icon} from '@/shell'
import type {IconName} from '@/shell'

import type {ServiceCounts, ServiceView} from './serviceTypes'

/**
 * The rest of a service, as tiles rather than as a strip of tabs.
 *
 * The tab strip at the top of the screen is the complete list and this is not a second
 * navigation: it is the same destinations at the bottom of the page, where a thumb is.
 * Eleven tabs do not fit on a 375px screen, so the strip scrolls sideways and the tabs
 * past the fourth are found by somebody who already knows they are there. A customer
 * looking for the terminal on a phone reaches the end of the overview long before they
 * think to drag a tab strip.
 *
 * Counts are on the three that have them, and only when they are not zero. A tile reading
 * "Volumes 0" is a tile saying nothing; "Volumes" with the empty state one tap away says
 * the same thing and leaves room for the label.
 */
interface Shortcut {
  href: string
  label: string
  icon: IconName
  hint: string
}

export function ServiceShortcuts({
  service,
  counts,
}: {
  service: ServiceView
  counts: ServiceCounts
}) {
  const id = service.id
  const shortcuts: Shortcut[] = [
    {
      href: `/services/${id}/deployments`,
      label: 'Deployments',
      icon: 'jobs',
      hint: service.site ? 'Build and publish a release' : 'Build and roll out a change',
    },
    {
      href: `/services/${id}/environment`,
      label: 'Environment',
      icon: 'key',
      hint: describeCount(counts.envVars + counts.secrets, 'variable', 'set'),
    },
    {
      href: `/services/${id}/volumes`,
      label: 'Volumes',
      icon: 'database',
      hint: service.site
        ? 'A site serves files, not disks'
        : describeCount(counts.volumes, 'volume', 'attached'),
    },
    {
      href: `/services/${id}/tasks`,
      label: 'Scheduled',
      icon: 'jobs',
      hint: describeCount(counts.scheduledTasks, 'command', 'scheduled'),
    },
    {
      href: `/services/${id}/domains`,
      label: 'Domains',
      icon: 'external',
      hint: 'Hostnames and certificates',
    },
    {
      href: `/services/${id}/files`,
      label: 'Files',
      icon: 'projects',
      hint: 'Browse, edit and upload',
    },
    {
      href: `/services/${id}/terminal`,
      label: 'Terminal',
      icon: 'monitor',
      hint: 'A shell inside the container',
    },
    {
      href: `/services/${id}/logs`,
      label: 'Logs',
      icon: 'audit',
      hint: 'What it is printing right now',
    },
    {
      href: `/services/${id}/metrics`,
      label: 'Metrics',
      icon: 'node',
      hint: 'CPU, memory and network',
    },
  ]

  return (
    <Card title="Everything else" padded={false}>
      <ul className="grid grid-cols-1 divide-y divide-ink-200 sm:grid-cols-2 sm:divide-y-0 dark:divide-ink-800">
        {shortcuts.map((shortcut) => (
          <li key={shortcut.href}>
            <Link
              href={shortcut.href}
              className="flex touch-target items-center gap-3 px-4 py-3 transition-colors hover:bg-ink-100 md:px-5 dark:hover:bg-ink-800"
            >
              <Icon name={shortcut.icon} className="size-5 shrink-0 text-ink-500" />
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium text-ink-900 dark:text-ink-100">
                  {shortcut.label}
                </span>
                <span className="block truncate text-xs text-ink-500 dark:text-ink-400">
                  {shortcut.hint}
                </span>
              </span>
              <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" />
            </Link>
          </li>
        ))}
      </ul>
    </Card>
  )
}

/** "3 volumes attached", or what to do when there are none. */
function describeCount(count: number, noun: string, verb: string): string {
  if (count === 0) {
    return `No ${noun}s ${verb} yet`
  }
  return `${count} ${count === 1 ? noun : `${noun}s`} ${verb}`
}
