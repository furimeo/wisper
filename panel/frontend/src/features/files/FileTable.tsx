import type {MouseEvent, ReactNode} from 'react'

import {ByteSize, Icon, RelativeTime, cx} from '@/shell'

import {FileGlyph} from './FileGlyph'
import type {MenuAnchor} from './FileActionMenu'
import type {FileSelection} from './fileSelection'
import {permissionText} from './fileKinds'
import type {SortKey, SortOrder} from './fileSorting'
import type {FileEntryView} from './fileTypes'

/**
 * The directory on a screen with room for it.
 *
 * A table is genuinely better than a list once forty rows fit at once: the eye compares
 * sizes and dates down a column, which is why a desktop file manager has looked like this
 * for thirty years. It is not what a phone gets, and only one of the two is ever mounted.
 *
 * Three behaviours here are the ones somebody who administers a server all day will notice
 * the absence of. Clicking a row selects it and does not open it - opening is a double
 * click or Enter, so a click that was meant to pick a file cannot walk into a folder.
 * Shift extends from the last row touched and Ctrl adds one without disturbing the rest,
 * which is how a selection is built anywhere else. And a right-click anywhere on a row
 * opens the same menu as the toolbar, on the row under the pointer, selecting it first if
 * it was not already part of the selection - because a context menu that acts on something
 * other than what was clicked is a data-loss bug waiting for a tired afternoon.
 */
export function FileTable({
  entries,
  selection,
  order,
  onSort,
  onOpen,
  onContextMenu,
  onOverflow,
  readOnlyNote,
}: {
  entries: FileEntryView[]
  selection: FileSelection
  order: SortOrder
  onSort: (key: SortKey) => void
  onOpen: (entry: FileEntryView) => void
  onContextMenu: (index: number, anchor: MenuAnchor) => void
  onOverflow: (index: number, anchor: MenuAnchor) => void
  /** Shown under the table when the customer cannot change anything in this tree. */
  readOnlyNote: string | null
}) {
  const pick = (index: number, event: MouseEvent) => {
    if (event.shiftKey) {
      selection.extendTo(index)
    } else if (event.ctrlKey || event.metaKey) {
      selection.toggle(index)
    } else {
      selection.select(index)
    }
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-left text-sm">
        <caption className="sr-only">Files in this folder</caption>
        <thead>
          <tr className="border-b border-ink-200 dark:border-ink-800">
            <th scope="col" className="w-10 px-3 py-2.5">
              <input
                type="checkbox"
                checked={selection.all}
                onChange={() => (selection.all ? selection.clear() : selection.selectAll())}
                aria-label={selection.all ? 'Clear the selection' : 'Select everything shown'}
                className="size-4 rounded border-ink-400 accent-accent-600 dark:border-ink-600"
              />
            </th>
            <SortableHeader label="Name" sortKey="name" order={order} onSort={onSort} />
            <SortableHeader label="Size" sortKey="size" order={order} onSort={onSort} align="right" />
            <SortableHeader label="Modified" sortKey="modified" order={order} onSort={onSort} />
            <HeaderCell>Permissions</HeaderCell>
            <th className="w-12 px-2" />
          </tr>
        </thead>
        <tbody className="divide-y divide-ink-200 dark:divide-ink-800">
          {entries.map((entry, index) => {
            const isSelected = selection.isSelected(entry.path)
            return (
              <tr
                key={entry.path}
                data-file-index={index}
                onClick={(event) => pick(index, event)}
                onDoubleClick={() => onOpen(entry)}
                onContextMenu={(event) => {
                  event.preventDefault()
                  if (!isSelected) {
                    selection.select(index)
                  } else {
                    selection.setCursor(index)
                  }
                  onContextMenu(index, {x: event.clientX, y: event.clientY})
                }}
                className={cx(
                  'cursor-default align-middle select-none',
                  isSelected
                    ? 'bg-accent-500/10'
                    : 'hover:bg-ink-50 dark:hover:bg-ink-800/50',
                  selection.cursor === index
                    ? 'outline -outline-offset-2 outline-accent-500'
                    : '',
                )}
              >
                <td className="px-3 py-2">
                  <input
                    type="checkbox"
                    checked={isSelected}
                    onClick={(event) => event.stopPropagation()}
                    onChange={() => selection.toggle(index)}
                    aria-label={`Select ${entry.name}`}
                    className="size-4 rounded border-ink-400 accent-accent-600 dark:border-ink-600"
                  />
                </td>
                <td className="max-w-0 px-3 py-2">
                  <span className="flex min-w-0 items-center gap-2">
                    <FileGlyph entry={entry} />
                    <span className="truncate font-medium text-ink-900 dark:text-ink-100">
                      {entry.name}
                    </span>
                    {entry.symlink ? (
                      <span className="shrink-0 text-xs font-normal text-ink-500">
                        → {entry.symlinkTarget ?? 'link'}
                      </span>
                    ) : null}
                  </span>
                </td>
                <td className="px-3 py-2 text-right tabular-nums text-ink-600 dark:text-ink-400">
                  {entry.directory ? '—' : <ByteSize bytes={entry.sizeBytes} />}
                </td>
                <td className="whitespace-nowrap px-3 py-2 text-ink-600 dark:text-ink-400">
                  <RelativeTime at={entry.modifiedAt} fallback="never" />
                </td>
                <td className="whitespace-nowrap px-3 py-2 font-mono text-xs text-ink-500 dark:text-ink-400">
                  {permissionText(entry.mode)}
                  <span className="ml-2 text-ink-400">{entry.modeOctal}</span>
                </td>
                <td className="px-2 py-2 text-right">
                  <button
                    type="button"
                    onClick={(event) => {
                      event.stopPropagation()
                      if (!isSelected) {
                        selection.select(index)
                      }
                      const box = event.currentTarget.getBoundingClientRect()
                      onOverflow(index, {x: box.right - 240, y: box.bottom + 4})
                    }}
                    aria-label={`Actions for ${entry.name}`}
                    className="inline-flex size-9 items-center justify-center rounded-lg text-ink-500 hover:bg-ink-200/60 dark:hover:bg-ink-700"
                  >
                    <Icon name="more" />
                  </button>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      {readOnlyNote ? (
        <p className="px-3 py-3 text-xs text-ink-500 dark:text-ink-400">{readOnlyNote}</p>
      ) : null}
    </div>
  )
}

function SortableHeader({
  label,
  sortKey,
  order,
  onSort,
  align,
}: {
  label: string
  sortKey: SortKey
  order: SortOrder
  onSort: (key: SortKey) => void
  align?: 'left' | 'right'
}) {
  const active = order.key === sortKey
  return (
    <th
      scope="col"
      aria-sort={active ? (order.direction === 'asc' ? 'ascending' : 'descending') : 'none'}
      className={cx('px-3 py-1.5', align === 'right' ? 'text-right' : '')}
    >
      <button
        type="button"
        onClick={() => onSort(sortKey)}
        className={cx(
          'inline-flex min-h-9 items-center gap-1 rounded-lg px-1 text-xs font-semibold uppercase tracking-wide',
          active
            ? 'text-ink-900 dark:text-ink-100'
            : 'text-ink-500 hover:text-ink-800 dark:text-ink-400 dark:hover:text-ink-200',
        )}
      >
        {label}
        {/* No chevron-up in the shell's set, and one glyph rotated is one glyph fewer to
            keep aligned with the other twenty than a near-duplicate path would be. */}
        <Icon
          name={active ? 'chevronDown' : 'chevronUpDown'}
          className={cx(
            'size-3.5',
            active ? (order.direction === 'asc' ? 'rotate-180' : '') : 'opacity-40',
          )}
        />
      </button>
    </th>
  )
}

function HeaderCell({children}: {children: ReactNode}) {
  return (
    <th
      scope="col"
      className="px-3 py-2.5 text-xs font-semibold uppercase tracking-wide text-ink-500 dark:text-ink-400"
    >
      {children}
    </th>
  )
}
