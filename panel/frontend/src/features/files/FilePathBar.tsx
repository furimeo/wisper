import {Link, router} from '@inertiajs/react'
import type {ReactNode} from 'react'
import {useEffect, useRef, useState} from 'react'

import {Badge, Button, Icon, Select, cx} from '@/shell'

import {browseHref} from './fileRequests'
import type {FileRootRef} from './fileTypes'

/**
 * Where you are, how to get above it, and how to jump somewhere else entirely.
 *
 * Three controls, because a file manager needs all three and a breadcrumb alone is only
 * the first. The crumbs are for going up one or two levels, which is most navigation. The
 * up arrow beside them is for the level immediately above, which is the one thing a
 * breadcrumb makes you aim for a small target to reach - and it is the mouse equivalent of
 * the Backspace this screen binds. The path field is for everything else: somebody who
 * knows their file is in `releases/2026-09-11/public` types it rather than opening four
 * folders, and somebody who pasted a path out of a deployment log needs somewhere to paste
 * it.
 *
 * The field is not always visible. A text input holding a path is the widest control on
 * the screen and it is used a fraction as often as the crumbs, so it appears when it is
 * asked for - by the button, or by Ctrl+L, which is where every browser and every file
 * manager has put "edit the location" for twenty years.
 */
export function FilePathBar({
  serviceId,
  roots,
  root,
  path,
  parentPath,
  showHidden,
  editing,
  onEditingChange,
}: {
  serviceId: string
  roots: FileRootRef[]
  root: FileRootRef
  /** Relative to the root; empty at the top. */
  path: string
  parentPath: string
  showHidden: boolean
  /** Held by the page so Ctrl+L can open the field from anywhere on the screen. */
  editing: boolean
  onEditingChange: (editing: boolean) => void
}) {
  const segments = path === '' ? [] : path.split('/')
  const atTop = path === ''

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

      {editing ? (
        <PathField
          serviceId={serviceId}
          rootId={root.id}
          path={path}
          showHidden={showHidden}
          onDone={() => onEditingChange(false)}
        />
      ) : (
        <div className="flex items-center gap-1">
          <Button
            variant="secondary"
            size="sm"
            disabled={atTop}
            onClick={() => router.visit(browseHref(serviceId, root.id, parentPath, showHidden))}
            title={atTop ? 'This is the top of the tree.' : 'Up one folder (Backspace)'}
            aria-label="Up one folder"
          >
            <Icon name="chevronLeft" className="size-4" />
          </Button>

          <nav
            aria-label="Folder path"
            className="hide-scrollbar min-w-0 flex-1 overflow-x-auto"
          >
            <ol className="flex w-max min-w-full items-center gap-1 text-sm">
              <Crumb
                href={browseHref(serviceId, root.id, '', showHidden)}
                current={atTop}
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

          <Button
            variant="secondary"
            size="sm"
            onClick={() => onEditingChange(true)}
            title="Type a path (Ctrl+L)"
            aria-label="Type a path"
          >
            <span aria-hidden="true" className="font-mono text-xs">
              /
            </span>
          </Button>
        </div>
      )}
    </div>
  )
}

/**
 * The path, as something to type in.
 *
 * Enter goes; Escape puts the crumbs back without moving. Leading and trailing slashes are
 * trimmed rather than refused - a path pasted out of a log or a `docker exec` starts with
 * one, and rejecting it would be technically correct and useless. Everything else is left
 * exactly as typed, because `RelativePath` on the server is the one place that decides
 * whether a path is inside the root, and a client that quietly rewrites `..` teaches
 * somebody a habit the server will refuse.
 */
function PathField({
  serviceId,
  rootId,
  path,
  showHidden,
  onDone,
}: {
  serviceId: string
  rootId: string
  path: string
  showHidden: boolean
  onDone: () => void
}) {
  const field = useRef<HTMLInputElement>(null)
  const [value, setValue] = useState(path)

  useEffect(() => {
    setValue(path)
    field.current?.focus()
    field.current?.select()
  }, [path])

  const go = () => {
    const target = value.trim().replace(/^\/+/, '').replace(/\/+$/, '')
    onDone()
    if (target !== path) {
      router.visit(browseHref(serviceId, rootId, target, showHidden))
    }
  }

  return (
    <form
      className="flex items-center gap-2"
      onSubmit={(event) => {
        event.preventDefault()
        go()
      }}
    >
      <span className="shrink-0 text-sm text-ink-500 dark:text-ink-400">Path</span>
      <input
        ref={field}
        value={value}
        onChange={(event) => setValue(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.preventDefault()
            event.stopPropagation()
            onDone()
          }
        }}
        aria-label="Folder path"
        placeholder="the top of this tree"
        autoCapitalize="off"
        autoCorrect="off"
        spellCheck={false}
        className={cx(
          'min-h-11 w-full min-w-0 flex-1 rounded-lg border px-3 font-mono text-base md:text-sm',
          'border-ink-300 bg-white text-ink-900 placeholder:text-ink-400',
          'dark:border-ink-700 dark:bg-ink-900 dark:text-ink-50',
        )}
      />
      <Button type="submit" size="sm">
        Go
      </Button>
      <Button variant="ghost" size="sm" onClick={onDone}>
        Cancel
      </Button>
    </form>
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
