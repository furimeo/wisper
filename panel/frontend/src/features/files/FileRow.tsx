import {ByteSize, Icon, RelativeTime, SwipeRow, cx} from '@/shell'
import type {SwipeAction} from '@/shell'

import {FileGlyph} from './FileGlyph'
import {swipeActionsFor} from './fileActions'
import type {FileActionContext, FileActionKind} from './fileActions'
import type {FileEntryView} from './fileTypes'

/**
 * One entry, as a phone shows it: a single column with the name, one supporting line and
 * a size.
 *
 * The four-column table a desktop file manager uses does not survive 375px - either it
 * scrolls sideways, hiding the modified date behind the name, or every cell becomes two
 * words. So on a phone the same information is a headline, a subtitle and a right-hand
 * value, and the actions live behind a swipe and an always-present overflow button.
 *
 * The row is one 44px-plus target and it is a button, not a link: tapping a folder is an
 * Inertia visit and tapping a file opens the editor, and only the caller knows which. The
 * checkbox is its own target beside it, so entering multi-select never happens by
 * accident on the way into a folder.
 */
export function FileRow({
  entry,
  context,
  selected,
  selecting,
  swipeOpen,
  onSwipeOpenChange,
  onToggleSelected,
  onAction,
  onOverflow,
}: {
  entry: FileEntryView
  context: FileActionContext
  selected: boolean
  /** True once anything is selected: the checkboxes stay visible while it is. */
  selecting: boolean
  swipeOpen: boolean
  onSwipeOpenChange: (open: boolean) => void
  onToggleSelected: () => void
  onAction: (kind: FileActionKind, entry: FileEntryView) => void
  onOverflow: () => void
}) {
  const swipe: SwipeAction[] = swipeActionsFor(entry, context).map((action) => ({
    label: action.label,
    tone: action.tone,
    onSelect: () => onAction(action.kind, entry),
  }))

  return (
    <SwipeRow actions={swipe} open={swipeOpen} onOpenChange={onSwipeOpenChange}>
      <div
        className={cx(
          'flex items-stretch',
          selected ? 'bg-accent-500/10' : '',
        )}
      >
        <label
          className="flex touch-target shrink-0 cursor-pointer items-center pl-3 pr-1"
          onClick={(event) => event.stopPropagation()}
        >
          <input
            type="checkbox"
            checked={selected}
            onChange={onToggleSelected}
            aria-label={`Select ${entry.name}`}
            className={cx(
              'size-5 rounded border-ink-400 accent-accent-600 dark:border-ink-600',
              selecting || selected ? '' : 'opacity-60',
            )}
          />
        </label>

        <button
          type="button"
          onClick={() =>
            onAction(entry.directory ? 'open' : 'edit', entry)
          }
          className="flex min-w-0 flex-1 items-center gap-3 px-2 py-3 text-left"
        >
          <FileGlyph entry={entry} />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-medium text-ink-900 dark:text-ink-100">
              {entry.name}
              {entry.symlink ? (
                <span className="ml-1.5 text-xs font-normal text-ink-500">
                  → {entry.symlinkTarget ?? 'link'}
                </span>
              ) : null}
            </span>
            <span className="mt-0.5 flex items-center gap-2 text-xs text-ink-500 dark:text-ink-400">
              <RelativeTime at={entry.modifiedAt} fallback="never modified" />
              <span aria-hidden="true">·</span>
              <span className="font-mono">{entry.modeOctal}</span>
            </span>
          </span>
          {entry.directory ? (
            <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" />
          ) : (
            <ByteSize
              bytes={entry.sizeBytes}
              className="shrink-0 text-xs tabular-nums text-ink-500 dark:text-ink-400"
            />
          )}
        </button>

        <button
          type="button"
          onClick={onOverflow}
          aria-label={`Actions for ${entry.name}`}
          className="flex touch-target shrink-0 items-center justify-center px-1 text-ink-500"
        >
          <Icon name="more" />
        </button>
      </div>
    </SwipeRow>
  )
}
