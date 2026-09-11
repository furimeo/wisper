import {Link} from '@inertiajs/react'

import {Icon} from './Icon'
import {cx} from './cx'
import {useBreadcrumbs} from './useBreadcrumbs'
import {useIsWide} from './useMediaQuery'

/**
 * The trail, and on a phone the way back up it.
 *
 * A service's file manager is four levels deep - Projects, the project, the service,
 * Files - and four crumbs plus separators do not fit across 375px without each one
 * shrinking to an ellipsis. So a phone shows the parent as a back link and the current
 * page as the title beneath it, which is the shape the platform's own navigation uses and
 * the one a thumb in the top-left corner expects. From `md` upward there is room for the
 * whole trail.
 */
export function Breadcrumbs({className}: {className?: string}) {
  const trail = useBreadcrumbs()
  const wide = useIsWide()

  if (trail.length === 0) {
    return null
  }

  const current = trail[trail.length - 1]
  const parent = trail.length > 1 ? trail[trail.length - 2] : undefined

  if (!wide) {
    return (
      <div className={cx('flex min-w-0 flex-col', className)}>
        {parent?.href ? (
          <Link
            href={parent.href}
            className="-ml-1 inline-flex items-center gap-0.5 self-start text-xs text-ink-500 dark:text-ink-400"
          >
            <Icon name="chevronLeft" className="size-3.5" />
            <span className="max-w-[60vw] truncate">{parent.label}</span>
          </Link>
        ) : null}
        <h1 className="truncate text-base font-semibold text-ink-900 dark:text-ink-100">
          {current?.label}
        </h1>
      </div>
    )
  }

  return (
    <nav aria-label="Breadcrumb" className={cx('min-w-0', className)}>
      <ol className="flex min-w-0 items-center gap-1 text-sm">
        {trail.map((crumb, index) => (
          <li key={`${crumb.label}-${index}`} className="flex min-w-0 items-center gap-1">
            {index > 0 ? (
              <Icon name="chevronRight" className="size-3.5 shrink-0 text-ink-400" />
            ) : null}
            {crumb.href ? (
              <Link
                href={crumb.href}
                className="max-w-48 truncate text-ink-500 hover:text-ink-900 dark:text-ink-400 dark:hover:text-ink-100"
              >
                {crumb.label}
              </Link>
            ) : (
              <span
                aria-current="page"
                className="max-w-72 truncate font-semibold text-ink-900 dark:text-ink-100"
              >
                {crumb.label}
              </span>
            )}
          </li>
        ))}
      </ol>
    </nav>
  )
}
