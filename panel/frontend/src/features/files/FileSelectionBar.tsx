import {Button, cx} from '@/shell'

import {FileActionIcon} from './FileActionIcon'
import type {FileActionKind, FileActionState} from './fileActions'

/**
 * What you can do to the rows you have picked, shown only once you have picked some.
 *
 * <p>Appearing on selection is the point. It keeps the resting screen down to a list and
 * two buttons, and it means every control here is one that applies right now - there is
 * nothing greyed out to squint at, because a bar that only exists when it is usable has
 * nothing to disable.
 *
 * <p>Four actions, and they are the four that mean something for more than one file at a
 * time. Rename asks for a name, permissions ask for a mode, and editing opens one file:
 * those belong to a single row and stay in that row's own menu. Offering them here would
 * mean either picking one of the selected files arbitrarily or a dialog that asks the same
 * question ten times.
 *
 * <p>Fixed to the bottom on a phone, where the list is long and the thumb is at the bottom
 * of the screen; inline above the list on a desktop, where the eye is already at the top.
 */
const BULK: FileActionKind[] = ['download', 'compress', 'move', 'delete']

export function FileSelectionBar({
  states,
  count,
  onAction,
  onClear,
  className,
}: {
  states: FileActionState[]
  count: number
  onAction: (kind: FileActionKind) => void
  onClear: () => void
  className?: string
}) {
  if (count === 0) {
    return null
  }

  const by = new Map(states.map((state) => [state.kind, state]))

  return (
    <div
      role="toolbar"
      aria-label={`${count} selected`}
      className={cx(
        'flex flex-wrap items-center gap-2 rounded-xl border px-3 py-2',
        'border-accent-500/40 bg-accent-500/10',
        className,
      )}
    >
      <span className="text-sm font-medium tabular-nums text-ink-800 dark:text-ink-100">
        {count.toLocaleString()} selected
      </span>

      <div className="ml-auto flex flex-wrap items-center gap-1">
        {BULK.map((kind) => {
          const state = by.get(kind)
          if (!state || state.disabledReason !== null) {
            return null
          }
          return (
            <Button
              key={kind}
              variant="ghost"
              size="sm"
              onClick={() => onAction(kind)}
              className={state.tone === 'danger' ? 'text-failed hover:bg-failed/10' : undefined}
            >
              <FileActionIcon kind={kind} />
              {state.label}
            </Button>
          )
        })}
        <Button variant="ghost" size="sm" onClick={onClear}>
          Clear
        </Button>
      </div>
    </div>
  )
}
