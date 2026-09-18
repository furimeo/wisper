import {Link} from '@inertiajs/react'

import {t} from '@/i18n'
import {Card, Icon} from '@/shell'
import type {IconName} from '@/shell'

import type {ServiceCounts, ServiceView} from './serviceTypes'

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
      label: t('service.tabs.deployments'),
      icon: 'jobs',
      hint: service.site
        ? t('service.shortcuts.deployments_hint_site')
        : t('service.shortcuts.deployments_hint_app'),
    },
    {
      href: `/services/${id}/environment`,
      label: t('service.shortcuts.environment'),
      icon: 'key',
      hint: describeCount(
        counts.envVars + counts.secrets,
        t('service.shortcuts.variable'),
        t('service.shortcuts.variables'),
        t('service.shortcuts.set'),
      ),
    },
    {
      href: `/services/${id}/volumes`,
      label: t('service.shortcuts.volumes'),
      icon: 'database',
      hint: service.site
        ? t('service.shortcuts.volumes_hint_site')
        : describeCount(
            counts.volumes,
            t('service.shortcuts.volume'),
            t('service.shortcuts.volumes_noun'),
            t('service.shortcuts.attached'),
          ),
    },
    {
      href: `/services/${id}/tasks`,
      label: t('service.shortcuts.scheduled'),
      icon: 'jobs',
      hint: describeCount(
        counts.scheduledTasks,
        t('service.shortcuts.command'),
        t('service.shortcuts.commands'),
        t('service.shortcuts.scheduled_verb'),
      ),
    },
    {
      href: `/services/${id}/domains`,
      label: t('service.shortcuts.domains'),
      icon: 'external',
      hint: t('service.shortcuts.domains_hint'),
    },
    {
      href: `/services/${id}/files`,
      label: t('service.shortcuts.files'),
      icon: 'projects',
      hint: t('service.shortcuts.files_hint'),
    },
    {
      href: `/services/${id}/terminal`,
      label: t('service.shortcuts.terminal'),
      icon: 'monitor',
      hint: t('service.shortcuts.terminal_hint'),
    },
    {
      href: `/services/${id}/logs`,
      label: t('service.shortcuts.logs'),
      icon: 'audit',
      hint: t('service.shortcuts.logs_hint'),
    },
    {
      href: `/services/${id}/metrics`,
      label: t('service.tabs.metrics'),
      icon: 'node',
      hint: t('service.shortcuts.metrics_hint'),
    },
  ]

  const visibleShortcuts = service.site
    ? shortcuts.filter(
        (s) =>
          !s.href.endsWith('/environment') &&
          !s.href.endsWith('/volumes') &&
          !s.href.endsWith('/tasks') &&
          !s.href.endsWith('/terminal'),
      )
    : shortcuts

  return (
    <Card title={t('service.shortcuts.title')} padded={false}>
      <ul className="grid grid-cols-1 divide-y divide-ink-200 sm:grid-cols-2 sm:divide-y-0 dark:divide-ink-800">
        {visibleShortcuts.map((shortcut) => (
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

function describeCount(count: number, singular: string, plural: string, verb: string): string {
  if (count === 0) {
    return t('service.shortcuts.no_items_yet', {noun: plural, verb})
  }
  return t('service.shortcuts.count_items', {count, items: count === 1 ? singular : plural, verb})
}
