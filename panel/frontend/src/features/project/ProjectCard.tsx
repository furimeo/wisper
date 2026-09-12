import {Link} from '@inertiajs/react'

import {t} from '@/i18n'
import {Badge, Icon, RelativeTime} from '@/shell'

import type {ProjectSummary} from './projectTypes'

/**
 * One project on the dashboard.
 *
 * A card rather than a table row because the dashboard is the screen most customers open
 * on a phone, and the two numbers that matter - how many services, how many of them up -
 * have to be readable without the header row a table needs to explain itself.
 *
 * The whole card is the link. A 375px-wide tap target is one nobody misses, and the
 * alternative is a chevron the size of a fingernail in the corner.
 */
export function ProjectCard({project}: {project: ProjectSummary}) {
  const running = project.runningCount
  const total = project.serviceCount
  const allUp = total > 0 && running === total

  return (
    <Link
      href={`/projects/${project.id}`}
      className="flex touch-target items-center gap-3 rounded-xl border border-ink-200 bg-white px-4 py-3.5 transition-colors hover:border-accent-500/60 dark:border-ink-800 dark:bg-ink-900"
    >
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate text-sm font-semibold text-ink-900 dark:text-ink-100">
            {project.name}
          </span>
          {project.archived ? <Badge tone="neutral">{t('project.card.archived')}</Badge> : null}
        </div>

        <p className="mt-0.5 truncate text-sm text-ink-500 dark:text-ink-400">
          {project.description || `/${project.slug}`}
        </p>

        <p className="mt-1.5 text-xs text-ink-500 dark:text-ink-400">
          {total === 0 ? (
            <>{t('project.card.empty')}</>
          ) : (
            <>
              {t('project.card.servicesCount', {count: total})}
              {project.archived ? null : (
                <>
                  {' · '}
                  <span className={allUp ? 'text-running' : running === 0 ? '' : 'text-degraded'}>
                    {t('project.card.running', {count: running})}
                  </span>
                </>
              )}
            </>
          )}
          {t('project.card.opened')}
          <RelativeTime at={project.createdAt} />
        </p>
      </div>

      <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" />
    </Link>
  )
}
