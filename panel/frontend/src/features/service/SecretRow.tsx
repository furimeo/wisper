import {Badge, Icon, RelativeTime} from '@/shell'

import type {SecretView} from './serviceTypes'

/**
 * One secret.
 *
 * There is no value on this row and no control that could produce one. `ListSecrets` does
 * not select the column, so the value is not in the page's props, not in a props dump and
 * not in a stack trace - the panel cannot show it back because it was never sent. The row
 * says when it was last changed instead, which is the question somebody actually has:
 * "did the rotation last Tuesday go through?"
 *
 * That is also why there is no copy button here and there is one on a plain variable. A
 * secret you can copy out of a panel is a secret stored somewhere it can be copied out
 * of.
 */
export function SecretRow({
  secret,
  onOpen,
}: {
  secret: SecretView
  /** Absent for a viewer. */
  onOpen?: () => void
}) {
  const body = (
    <>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2">
          <code className="truncate font-mono text-sm font-medium text-ink-900 dark:text-ink-100">
            {secret.name}
          </code>
          {secret.buildTime ? <Badge tone="accent">Build</Badge> : null}
        </span>
        <span className="mt-0.5 block truncate text-xs text-ink-500 dark:text-ink-400">
          {secret.lastChangedAt ? (
            <>
              Last changed <RelativeTime at={secret.lastChangedAt} />
            </>
          ) : (
            <>
              Set <RelativeTime at={secret.createdAt} /> and unchanged since
            </>
          )}
        </span>
      </span>
      {onOpen ? <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" /> : null}
    </>
  )

  return (
    <li>
      {onOpen ? (
        <button
          type="button"
          onClick={onOpen}
          className="flex w-full touch-target items-center gap-3 px-4 py-2.5 text-left transition-colors hover:bg-ink-100 md:px-5 dark:hover:bg-ink-800"
        >
          {body}
        </button>
      ) : (
        <div className="flex w-full touch-target items-center gap-3 px-4 py-2.5 md:px-5">
          {body}
        </div>
      )}
    </li>
  )
}
