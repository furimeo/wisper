import {useEffect, useRef, useState} from 'react'

import {t} from '@/i18n'
import {Button, EmptyState, ErrorState, Spinner, useIsWide} from '@/shell'

import {FileRow} from './FileRow'
import {FileTable} from './FileTable'
import type {MenuAnchor} from './FileActionMenu'
import {actionStates} from './fileActions'
import type {FileActionContext, FileActionKind, FileActionState} from './fileActions'
import type {FileSelection} from './fileSelection'
import {DEFAULT_SORT} from './fileSorting'
import type {SortKey, SortOrder} from './fileSorting'
import type {FileEntryView} from './fileTypes'

/**
 * The directory listing, in whichever shape the screen has room for.
 *
 * One component chooses between the phone list and the desktop table because they share
 * everything that is not layout: the selection, the menu and the paging. The choice is
 * made from the viewport rather than with CSS, so only one of the two ever mounts - forty
 * rows rendered twice is forty swipe handlers nobody can see.
 *
 * Paging is a button and not an infinite scroll. A customer with a `node_modules` has a
 * hundred thousand entries and the node hands them over a page at a time; a list that
 * keeps loading as you scroll makes it impossible to reach anything below it, and on a
 * phone it eats a data allowance while somebody is looking for one file.
 *
 * The footer is where this screen is honest about the consequence: sorting happens in the
 * browser, so on a directory with more pages it ranks what has been fetched and says so.
 * A "largest first" that silently ranks the first two hundred of forty thousand entries is
 * worse than no sorting at all.
 */
export function FileList({
  entries,
  total,
  selection,
  order,
  onSort,
  context,
  onOpen,
  onAction,
  onMenu,
  hasMore,
  loadingMore,
  loadError,
  onLoadMore,
  showingHidden,
  readOnlyNote,
}: {
  /** Already sorted; the index of a row here is the index the selection speaks in. */
  entries: FileEntryView[]
  /** How many the directory holds in full, so the footer can be honest about the rest. */
  total: number
  selection: FileSelection
  order: SortOrder
  onSort: (key: SortKey) => void
  context: FileActionContext
  onOpen: (entry: FileEntryView) => void
  onAction: (kind: FileActionKind, entries: FileEntryView[]) => void
  /** Opens the shared action menu; a null anchor means the sheet. */
  onMenu: (anchor: MenuAnchor | null) => void
  hasMore: boolean
  loadingMore: boolean
  loadError: string | null
  onLoadMore: () => void
  showingHidden: boolean
  readOnlyNote: string | null
}) {
  const wide = useIsWide()
  const [swipeOpen, setSwipeOpen] = useState<string | null>(null)
  const container = useRef<HTMLDivElement>(null)

  /*
   * Follow the keyboard. Arrowing past the bottom of the window without this leaves the
   * customer driving a list they cannot see, which is the single thing that makes a
   * keyboard-driven file manager unusable rather than merely unfashionable.
   */
  const cursor = selection.cursor
  useEffect(() => {
    if (cursor < 0) {
      return
    }
    container.current
      ?.querySelector(`[data-file-index="${cursor}"]`)
      ?.scrollIntoView({block: 'nearest'})
  }, [cursor])

  if (entries.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center p-8 text-center">
        <EmptyState
          title={t('files.list.empty_title')}
          description={
            showingHidden
              ? t('files.list.empty_desc_all')
              : t('files.list.empty_desc_hidden')
          }
        />
        {context.canWrite && !context.unavailable ? (
          <div className="mt-4 flex flex-wrap items-center justify-center gap-2">
            <Button size="sm" onClick={() => onAction('upload', [])}>
              {t('files.actions.upload')}
            </Button>
            <Button size="sm" variant="secondary" onClick={() => onAction('newFile', [])}>
              {t('files.actions.new_file')}
            </Button>
            <Button size="sm" variant="secondary" onClick={() => onAction('newFolder', [])}>
              {t('files.actions.new_folder')}
            </Button>
          </div>
        ) : null}
      </div>
    )
  }

  return (
    <div ref={container}>
      {wide ? (
        <FileTable
          entries={entries}
          selection={selection}
          order={order}
          onSort={onSort}
          onOpen={onOpen}
          onContextMenu={(_index, anchor) => onMenu(anchor)}
          onOverflow={(_index, anchor) => onMenu(anchor)}
          readOnlyNote={readOnlyNote}
        />
      ) : (
        <ul className="divide-y divide-ink-200 dark:divide-ink-800">
          {entries.map((entry, index) => (
            <li key={entry.path} data-file-index={index}>
              <FileRow
                entry={entry}
                selected={selection.isSelected(entry.path)}
                selecting={selection.count > 0}
                focused={selection.cursor === index}
                swipeOpen={swipeOpen === entry.path}
                onSwipeOpenChange={(open) => setSwipeOpen(open ? entry.path : null)}
                onToggleSelected={() => selection.toggle(index)}
                onOpen={() => onOpen(entry)}
                onAction={(kind) => {
                  setSwipeOpen(null)
                  onAction(kind, [entry])
                }}
                onOverflow={() => {
                  setSwipeOpen(null)
                  if (!selection.isSelected(entry.path)) {
                    selection.select(index)
                  }
                  onMenu(null)
                }}
                swipeActions={swipeActionsFor(entry, context)}
              />
            </li>
          ))}
        </ul>
      )}

      {loadError ? (
        <ErrorState
          title={t('files.list.load_error_title')}
          description={loadError}
          onRetry={onLoadMore}
          retryLabel={t('files.list.load_error_retry')}
        />
      ) : null}

      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-ink-200 px-4 py-3 text-xs text-ink-500 dark:border-ink-800 dark:text-ink-400">
        <span className="tabular-nums">
          {total > entries.length
            ? t('files.list.showing_of', {
                shown: entries.length.toLocaleString(),
                total: total.toLocaleString(),
              })
            : t('files.list.count_entries', {count: entries.length.toLocaleString()})}
          {hasMore && !isDefaultOrder(order)
            ? t('files.list.sorted_note')
            : ''}
        </span>
        {hasMore ? (
          <Button variant="secondary" size="sm" onClick={onLoadMore} disabled={loadingMore}>
            {loadingMore ? <Spinner /> : null}
            {t('files.list.load_more')}
          </Button>
        ) : null}
      </div>
    </div>
  )
}

function isDefaultOrder(order: SortOrder): boolean {
  return order.key === DEFAULT_SORT.key && order.direction === DEFAULT_SORT.direction
}

/**
 * The two actions a swipe reveals.
 *
 * A swipe is a shortcut and cannot hold a menu: past two buttons on a 375px screen the row
 * travels further than a thumb does comfortably, and every one of them is in the sheet
 * anyway. The most useful read action, and delete.
 */
function swipeActionsFor(
  entry: FileEntryView,
  context: FileActionContext,
): FileActionState[] {
  const available = actionStates([entry], context).filter(
    (state) => state.disabledReason === null,
  )
  const primary = available.find(
    (state) => state.kind === 'edit' || state.kind === 'download' || state.kind === 'open',
  )
  const remove = available.find((state) => state.kind === 'delete')
  return [primary, remove].filter((state): state is FileActionState => state !== undefined)
}
