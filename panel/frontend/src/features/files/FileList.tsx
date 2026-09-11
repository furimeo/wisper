import {useState} from 'react'

import {Button, Drawer, EmptyState, ErrorState, Spinner, cx, useIsWide} from '@/shell'

import {FileRow} from './FileRow'
import {FileTable} from './FileTable'
import {actionsFor} from './fileActions'
import type {FileActionContext, FileActionKind} from './fileActions'
import type {FileEntryView} from './fileTypes'

/**
 * The directory listing, in whichever shape the screen has room for.
 *
 * One component chooses between the phone list and the desktop table because they share
 * everything that is not layout: the selection, the action menu and the paging button.
 * The choice is made from the viewport rather than with CSS, so only one of the two ever
 * mounts - forty rows rendered twice is forty swipe handlers nobody can see.
 *
 * Paging is a button and not an infinite scroll. A customer with a `node_modules` has a
 * hundred thousand entries and the node hands them over two hundred at a time; a list
 * that keeps loading as you scroll makes it impossible to reach anything below it, and on
 * a phone it eats a data allowance while somebody is looking for one file.
 */
export function FileList({
  entries,
  total,
  context,
  selected,
  onToggleSelected,
  onToggleAll,
  onAction,
  hasMore,
  loadingMore,
  loadError,
  onLoadMore,
  showingHidden,
}: {
  entries: FileEntryView[]
  /** How many the directory holds in full, so the footer can be honest about the rest. */
  total: number
  context: FileActionContext
  selected: ReadonlySet<string>
  onToggleSelected: (entry: FileEntryView) => void
  onToggleAll: () => void
  onAction: (kind: FileActionKind, entry: FileEntryView) => void
  hasMore: boolean
  loadingMore: boolean
  loadError: string | null
  onLoadMore: () => void
  showingHidden: boolean
}) {
  const wide = useIsWide()
  const [swipeOpen, setSwipeOpen] = useState<string | null>(null)
  const [menuFor, setMenuFor] = useState<FileEntryView | null>(null)

  const menuActions = menuFor ? actionsFor(menuFor, context) : []

  if (entries.length === 0) {
    return (
      <EmptyState
        title="This folder is empty"
        description={
          showingHidden
            ? 'Nothing here at all, including dotfiles. Upload something, or create a folder to put it in.'
            : 'Nothing here, though dotfiles are hidden - turn them on if you are looking for one. Otherwise, upload a file or create a folder.'
        }
      />
    )
  }

  return (
    <div>
      {wide ? (
        <FileTable
          entries={entries}
          context={context}
          selected={selected}
          onToggleSelected={onToggleSelected}
          onToggleAll={onToggleAll}
          onAction={onAction}
          onOverflow={setMenuFor}
        />
      ) : (
        <ul className="divide-y divide-ink-200 dark:divide-ink-800">
          {entries.map((entry) => (
            <li key={entry.path}>
              <FileRow
                entry={entry}
                context={context}
                selected={selected.has(entry.path)}
                selecting={selected.size > 0}
                swipeOpen={swipeOpen === entry.path}
                onSwipeOpenChange={(open) => setSwipeOpen(open ? entry.path : null)}
                onToggleSelected={() => onToggleSelected(entry)}
                onAction={onAction}
                onOverflow={() => setMenuFor(entry)}
              />
            </li>
          ))}
        </ul>
      )}

      {loadError ? (
        <ErrorState
          title="The rest of this folder did not arrive"
          description={loadError}
          onRetry={onLoadMore}
          retryLabel="Try again"
        />
      ) : null}

      <div className="flex items-center justify-between gap-3 border-t border-ink-200 px-4 py-3 text-xs text-ink-500 dark:border-ink-800 dark:text-ink-400">
        <span className="tabular-nums">
          {total > entries.length
            ? `Showing ${entries.length.toLocaleString()} of ${total.toLocaleString()}`
            : `${entries.length.toLocaleString()} ${entries.length === 1 ? 'entry' : 'entries'}`}
        </span>
        {hasMore ? (
          <Button variant="secondary" size="sm" onClick={onLoadMore} disabled={loadingMore}>
            {loadingMore ? <Spinner /> : null}
            Load more
          </Button>
        ) : null}
      </div>

      <Drawer
        open={menuFor !== null}
        onClose={() => setMenuFor(null)}
        side="right"
        title={menuFor?.name ?? 'Actions'}
      >
        <ul className="flex flex-col">
          {menuActions.map((action) => (
            <li key={action.kind}>
              <button
                type="button"
                onClick={() => {
                  const entry = menuFor
                  setMenuFor(null)
                  setSwipeOpen(null)
                  if (entry) {
                    onAction(action.kind, entry)
                  }
                }}
                className={cx(
                  'flex w-full touch-target items-center gap-3 rounded-lg px-3 text-left text-sm',
                  action.tone === 'danger'
                    ? 'text-failed hover:bg-failed/10'
                    : 'text-ink-800 hover:bg-ink-100 dark:text-ink-100 dark:hover:bg-ink-800',
                )}
              >
                {action.label}
              </button>
            </li>
          ))}
        </ul>
      </Drawer>
    </div>
  )
}
