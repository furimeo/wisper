import {t} from '@/i18n'
import {ByteSize, Icon, RelativeTime, SwipeRow, cx} from '@/shell'
import type {SwipeAction} from '@/shell'

import {FileGlyph} from './FileGlyph'
import type {FileActionKind, FileActionState} from './fileActions'
import type {FileEntryView} from './fileTypes'

/**
 * One entry, as a phone shows it: a single column with the name, one supporting line and
 * a size.
 *
 * The four-column table a desktop file manager uses does not survive 375px - either it
 * scrolls sideways, hiding the modified date behind the name, or every cell becomes two
 * words. So on a phone the same information is a headline, a subtitle and a right-hand
 * value, with the actions behind a swipe and an always-present overflow button.
 *
 * A tap opens; the checkbox beside it selects. That is the opposite of the desktop table,
 * where a click selects and a double click opens, and the difference is deliberate: there
 * is no double tap to spend here, and a phone user walking into a folder should not have
 * to aim at a chevron. Entering multi-select is therefore always an explicit tap on a
 * 44px checkbox, which is also why it never happens on the way into a folder.
 */
export function FileRow({
  entry,
  selected,
  selecting,
  focused,
  swipeOpen,
  onSwipeOpenChange,
  onToggleSelected,
  onOpen,
  onAction,
  onOverflow,
  swipeActions,
}: {
  entry: FileEntryView
  selected: boolean
  /** True once anything is selected: the checkboxes stay visible while it is. */
  selecting: boolean
  /** The keyboard is on this row. Rare on a phone, and real on a tablet with a keyboard. */
  focused: boolean
  swipeOpen: boolean
  onSwipeOpenChange: (open: boolean) => void
  onToggleSelected: () => void
  onOpen: () => void
  onAction: (kind: FileActionKind) => void
  onOverflow: () => void
  /** At most two, already filtered to what this entry allows. */
  swipeActions: FileActionState[]
}) {
  const swipe: SwipeAction[] = swipeActions.map((action) => ({
    label: action.label,
    tone: action.tone,
    onSelect: () => onAction(action.kind),
  }))

  return (
    <SwipeRow actions={swipe} open={swipeOpen} onOpenChange={onSwipeOpenChange}>
      <div
        className={cx(
          'flex items-stretch',
          selected ? 'bg-accent-500/10' : '',
          focused ? 'outline -outline-offset-2 outline-accent-500' : '',
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
            aria-label={t('files.row.aria_select_entry', {name: entry.name})}
            className={cx(
              'size-5 rounded border-ink-400 accent-accent-600 dark:border-ink-600',
              selecting || selected ? '' : 'opacity-60',
            )}
          />
        </label>

        <button
          type="button"
          onClick={onOpen}
          onContextMenu={(event) => {
            // A tablet with a mouse, and a long-press on Android, both arrive here.
            event.preventDefault()
            onOverflow()
          }}
          className="flex min-w-0 flex-1 items-center gap-3 px-2 py-3 text-left"
        >
          <FileGlyph entry={entry} />
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-medium text-ink-900 dark:text-ink-100">
              {entry.name}
              {entry.symlink ? (
                <span className="ml-1.5 text-xs font-normal text-ink-500">
                  {t('files.table.link_target', {target: entry.symlinkTarget ?? t('files.table.link_fallback')})}
                </span>
              ) : null}
            </span>
            <span className="mt-0.5 flex items-center gap-2 text-xs text-ink-500 dark:text-ink-400">
              <RelativeTime at={entry.modifiedAt} fallback={t('files.row.never_modified')} />
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
          aria-label={t('files.row.aria_actions_for', {name: entry.name})}
          className="flex touch-target shrink-0 items-center justify-center px-1 text-ink-500"
        >
          <Icon name="more" />
        </button>
      </div>
    </SwipeRow>
  )
}
