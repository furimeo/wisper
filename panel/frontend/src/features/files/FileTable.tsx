import type {ReactNode} from 'react'

import {ByteSize, Icon, RelativeTime, cx} from '@/shell'

import {FileGlyph} from './FileGlyph'
import {permissionText} from './fileKinds'
import type {FileActionContext, FileActionKind} from './fileActions'
import type {FileEntryView} from './fileTypes'

/**
 * The same directory on a screen with room for it.
 *
 * A table is genuinely better than a list once forty rows fit at once: the eye compares
 * sizes and dates down a column, which is the whole reason a desktop file manager has
 * looked like this for thirty years. It is not what a phone gets, and only one of the two
 * is ever mounted - a hidden copy would double the rows the browser lays out and
 * everything a screen reader has to walk past.
 *
 * Permissions get a column here and only the octal digits on a phone, because
 * `rwxr-xr-x` is nine characters of width that a 375px row does not have and the four
 * digits are what somebody types into the chmod field anyway.
 */
export function FileTable({
  entries,
  context,
  selected,
  onToggleSelected,
  onToggleAll,
  onAction,
  onOverflow,
}: {
  entries: FileEntryView[]
  context: FileActionContext
  selected: ReadonlySet<string>
  onToggleSelected: (entry: FileEntryView) => void
  onToggleAll: () => void
  onAction: (kind: FileActionKind, entry: FileEntryView) => void
  onOverflow: (entry: FileEntryView) => void
}) {
  const allSelected = entries.length > 0 && entries.every((entry) => selected.has(entry.path))

  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-left text-sm">
        <caption className="sr-only">Files in this folder</caption>
        <thead>
          <tr className="border-b border-ink-200 dark:border-ink-800">
            <th scope="col" className="w-10 px-3 py-2.5">
              <input
                type="checkbox"
                checked={allSelected}
                onChange={onToggleAll}
                aria-label={allSelected ? 'Clear the selection' : 'Select everything shown'}
                className="size-4 rounded border-ink-400 accent-accent-600 dark:border-ink-600"
              />
            </th>
            <HeaderCell>Name</HeaderCell>
            <HeaderCell align="right">Size</HeaderCell>
            <HeaderCell>Modified</HeaderCell>
            <HeaderCell>Permissions</HeaderCell>
            <th className="w-12 px-2" />
          </tr>
        </thead>
        <tbody className="divide-y divide-ink-200 dark:divide-ink-800">
          {entries.map((entry) => {
            const isSelected = selected.has(entry.path)
            return (
              <tr
                key={entry.path}
                className={cx(
                  'align-middle',
                  isSelected
                    ? 'bg-accent-500/10'
                    : 'hover:bg-ink-50 dark:hover:bg-ink-800/50',
                )}
              >
                <td className="px-3 py-2">
                  <input
                    type="checkbox"
                    checked={isSelected}
                    onChange={() => onToggleSelected(entry)}
                    aria-label={`Select ${entry.name}`}
                    className="size-4 rounded border-ink-400 accent-accent-600 dark:border-ink-600"
                  />
                </td>
                <td className="px-3 py-2">
                  <button
                    type="button"
                    onClick={() => onAction(entry.directory ? 'open' : 'edit', entry)}
                    className="flex min-w-0 items-center gap-2 text-left font-medium text-ink-900 hover:text-accent-600 dark:text-ink-100 dark:hover:text-accent-400"
                  >
                    <FileGlyph entry={entry} />
                    <span className="truncate">{entry.name}</span>
                    {entry.symlink ? (
                      <span className="shrink-0 text-xs font-normal text-ink-500">
                        → {entry.symlinkTarget ?? 'link'}
                      </span>
                    ) : null}
                  </button>
                </td>
                <td className="px-3 py-2 text-right tabular-nums text-ink-600 dark:text-ink-400">
                  {entry.directory ? '—' : <ByteSize bytes={entry.sizeBytes} />}
                </td>
                <td className="px-3 py-2 text-ink-600 dark:text-ink-400">
                  <RelativeTime at={entry.modifiedAt} fallback="never" />
                </td>
                <td className="px-3 py-2 font-mono text-xs text-ink-500 dark:text-ink-400">
                  {permissionText(entry.mode)}
                  <span className="ml-2 text-ink-400">{entry.modeOctal}</span>
                </td>
                <td className="px-2 py-2 text-right">
                  <button
                    type="button"
                    onClick={() => onOverflow(entry)}
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
      {context.canWrite ? null : (
        <p className="px-3 py-3 text-xs text-ink-500 dark:text-ink-400">
          This tree is read-only for you, so the menus offer looking and downloading and
          nothing that would change it.
        </p>
      )}
    </div>
  )
}

function HeaderCell({
  children,
  align,
}: {
  children: ReactNode
  align?: 'left' | 'right'
}) {
  return (
    <th
      scope="col"
      className={cx(
        'px-3 py-2.5 text-xs font-semibold uppercase tracking-wide',
        'text-ink-500 dark:text-ink-400',
        align === 'right' ? 'text-right' : '',
      )}
    >
      {children}
    </th>
  )
}
