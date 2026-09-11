import {Link, router} from '@inertiajs/react'
import type {ReactNode} from 'react'

import {Badge, Icon, Select, cx} from '@/shell'

import {browseHref} from './fileRequests'
import type {FileRootRef} from './fileTypes'

/**
 * Where you are, and how to get anywhere above it.
 *
 * A breadcrumb rather than a "parent" button, because a customer three levels into a
 * release tree wants the root, not two taps. It scrolls sideways on a phone and the
 * current folder is pinned at the end - horizontal scrolling is acceptable for a strip
 * that is obviously a strip and nowhere else in this panel.
 *
 * The root picker sits above it and only appears when there is a choice to make. A
 * service with one volume has one root, and a select with one option is a control that
 * teaches somebody there is a decision here when there is not.
 */
export function FilePathBar({
  serviceId,
  roots,
  root,
  path,
  showHidden,
}: {
  serviceId: string
  roots: FileRootRef[]
  root: FileRootRef
  /** Relative to the root; empty at the top. */
  path: string
  showHidden: boolean
}) {
  const segments = path === '' ? [] : path.split('/')

  return (
    <div className="flex flex-col gap-2">
      {roots.length > 1 ? (
        <Select
          label="Folder tree"
          value={root.id}
          onChange={(event) => {
            router.visit(browseHref(serviceId, event.target.value, '', showHidden))
          }}
          options={roots.map((candidate) => ({
            value: candidate.id,
            label: candidate.writable ? candidate.label : `${candidate.label} (read-only)`,
          }))}
        />
      ) : null}

      <nav
        aria-label="Folder path"
        className="hide-scrollbar -mx-4 overflow-x-auto px-4 md:mx-0 md:px-0"
      >
        <ol className="flex w-max min-w-full items-center gap-1 text-sm">
          <Crumb
            href={browseHref(serviceId, root.id, '', showHidden)}
            current={segments.length === 0}
          >
            <Icon name="projects" className="size-4" />
            <span className="max-w-[9rem] truncate">{root.label}</span>
          </Crumb>

          {segments.map((segment, index) => {
            const upTo = segments.slice(0, index + 1).join('/')
            return (
              <li key={upTo} className="flex items-center gap-1">
                <Icon name="chevronRight" className="size-3.5 shrink-0 text-ink-400" />
                <Crumb
                  href={browseHref(serviceId, root.id, upTo, showHidden)}
                  current={index === segments.length - 1}
                >
                  <span className="max-w-[11rem] truncate">{segment}</span>
                </Crumb>
              </li>
            )
          })}

          {root.writable ? null : (
            <li className="ml-2">
              <Badge tone="neutral">Read-only</Badge>
            </li>
          )}
        </ol>
      </nav>
    </div>
  )
}

function Crumb({
  href,
  current,
  children,
}: {
  href: string
  current: boolean
  children: ReactNode
}) {
  const classes = cx(
    'inline-flex min-h-9 items-center gap-1.5 rounded-lg px-2',
    current
      ? 'font-semibold text-ink-900 dark:text-ink-100'
      : 'text-ink-600 hover:bg-ink-200/60 dark:text-ink-400 dark:hover:bg-ink-800',
  )

  if (current) {
    return (
      <li aria-current="page" className={classes}>
        {children}
      </li>
    )
  }
  return (
    <li>
      <Link href={href} className={classes}>
        {children}
      </Link>
    </li>
  )
}
