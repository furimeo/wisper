import {Head, router, usePage} from '@inertiajs/react'
import {Suspense, lazy, useCallback, useEffect, useMemo, useState} from 'react'

import {
  Card,
  Checkbox,
  ErrorState,
  PageHeader,
  Spinner,
  askConfirmation,
  toast,
} from '@/shell'

import {ServiceTabs} from '@/features/service/ServiceTabs'
import type {ServiceLocation} from '@/features/service/serviceTypes'

import {ArchiveDialog} from './ArchiveDialog'
import {DirectorySizeDialog} from './DirectorySizeDialog'
import {ExtractDialog} from './ExtractDialog'
import {FileActionMenu} from './FileActionMenu'
import type {MenuAnchor} from './FileActionMenu'
import {FileList} from './FileList'
import {FilePathBar} from './FilePathBar'
import {FileToolbar} from './FileToolbar'
import {MoveDialog} from './MoveDialog'
import {NewFolderDialog} from './NewFolderDialog'
import {PermissionsDialog} from './PermissionsDialog'
import {RenameDialog} from './RenameDialog'
import {UploadDialog} from './UploadDialog'
import {UploadDropOverlay} from './UploadDropOverlay'
import {deletePaths} from './deletePaths'
import {actionStates} from './fileActions'
import type {FileActionKind} from './fileActions'
import {isEditable} from './fileKinds'
import {FileRequestFailed, browseHref, downloadHref, listDirectory} from './fileRequests'
import {useFileSelection} from './fileSelection'
import {DEFAULT_SORT, nextOrder, sortEntries} from './fileSorting'
import type {SortKey} from './fileSorting'
import type {DirectoryPage, FileEntryView, FileRootRef} from './fileTypes'
import {useChunkedUpload} from './useChunkedUpload'
import {useFileShortcuts} from './useFileShortcuts'

/*
 * CodeMirror is about a third of a megabyte and nobody opening a folder listing has asked
 * for it yet. Splitting it here is what `vite.config.ts` means by "the terminal and the
 * editor are split out by the dynamic imports in their features".
 */
const FileEditor = lazy(() =>
  import('./FileEditor').then((module) => ({default: module.FileEditor})),
)

/** Which overlay is up. One value, because two of them on screen at once is never right. */
type Overlay =
  | {kind: 'none'}
  | {kind: 'newFolder'}
  | {kind: 'upload'}
  | {kind: 'rename'; entry: FileEntryView}
  | {kind: 'move'; entries: FileEntryView[]}
  | {kind: 'chmod'; entry: FileEntryView}
  | {kind: 'compress'; paths: string[]}
  | {kind: 'extract'; entry: FileEntryView}
  | {kind: 'measure'; path: string}
  | {kind: 'edit'; entry: FileEntryView}

type FileManagerProps = {
  service: ServiceLocation
  roots: FileRootRef[]
  root: FileRootRef
  path: string
  parentPath: string
  canWrite: boolean
  showHidden: boolean
  chunkSize: number
  maxEditableBytes: number
  page: DirectoryPage
  unavailable: string | null
}

/**
 * `GET /services/{serviceId}/files` - the whole of a customer's access to their disk.
 *
 * There is no SSH, no SFTP and no WebDAV in wisper, so this screen is not a convenience
 * next to a shell; it is the only way in (design §8.2). It is therefore shaped like a file
 * manager and not like a form with a list attached: a path bar, a toolbar whose controls
 * are always in the same place, a list that sorts and multi-selects, a right-click menu, a
 * keyboard that can drive all of it, and an editor that fills the window.
 *
 * Four things are worth knowing before changing it.
 *
 * **`unavailable` is a real state and is not "no files".** A service with no volumes, a
 * service that has never been started and a node that cannot be reached all produce an
 * empty listing, and the controller sends the sentence that tells them apart. Rendering an
 * empty folder instead would tell a customer their files are gone.
 *
 * **The first page comes in the props.** A page that arrives empty and then fetches shows a
 * spinner on every navigation, which on mobile data is most of the experience. Later pages
 * are `fetch`, because scrolling is not a navigation.
 *
 * **Uploads outlive this component.** They live in `uploadQueue.ts`, so walking into
 * another folder while a 400 MB file goes up does not cancel it - and neither does closing
 * the upload dialog, which is why that dialog can be a dialog at all.
 *
 * **The selection speaks in list indices.** Everything below the toolbar shares one sorted
 * array, so a shift-click range, the keyboard cursor and the right-click menu all mean the
 * same rows. Sorting is applied here rather than at the node, which is honest only because
 * `FileList` says so when a directory has more pages.
 *
 * Over three hundred lines, deliberately (AGENTS.md §3.2). What is left after the list, the
 * rows, the toolbar, the menu, the selection, the sorting, the shortcuts, the upload engine,
 * the editor and each dialog were moved out is this screen's own state and the switch that
 * routes an action to the right overlay - and splitting *that* means a dozen pieces of
 * state and their setters crossing a component boundary, which is more code in two files
 * than it is in one.
 */
export default function FileManagerPage() {
  const props = usePage<FileManagerProps>().props
  const {service, roots, root, path, parentPath, canWrite, showHidden, maxEditableBytes} = props
  const {page, unavailable} = props
  const serviceId = service.serviceId

  const [loaded, setLoaded] = useState<FileEntryView[]>(page.entries)
  const [cursor, setCursor] = useState<string | null>(page.nextCursor)
  const [loadingMore, setLoadingMore] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const [order, setOrder] = useState(DEFAULT_SORT)
  const [overlay, setOverlay] = useState<Overlay>({kind: 'none'})
  const [menu, setMenu] = useState<{anchor: MenuAnchor | null} | null>(null)
  const [editingPath, setEditingPath] = useState(false)

  const entries = useMemo(() => sortEntries(loaded, order), [loaded, order])
  const selection = useFileSelection(entries)
  const {clear} = selection

  // A new server response - a navigation, or the redirect after a write - replaces
  // whatever "load more" had appended. Keyed on the prop object, which Inertia hands over
  // fresh on every visit.
  useEffect(() => {
    setLoaded(page.entries)
    setCursor(page.nextCursor)
    setLoadError(null)
    clear()
  }, [page, clear])

  const refresh = useCallback(() => {
    router.reload({
      only: ['page'],
      onStart: () => setRefreshing(true),
      onFinish: () => setRefreshing(false),
    })
  }, [])

  const uploads = useChunkedUpload(serviceId, root.id, path, refresh)

  const context = {
    canWrite,
    maxEditableBytes,
    unavailable: unavailable !== null,
  }
  const states = actionStates(selection.entries, context)

  const loadMore = () => {
    if (!cursor) {
      return
    }
    setLoadingMore(true)
    setLoadError(null)
    listDirectory(serviceId, root.id, path, cursor, showHidden)
      .then((next) => {
        setLoaded((current) => [...current, ...next.entries])
        setCursor(next.nextCursor)
      })
      .catch((cause: unknown) => {
        setLoadError(
          cause instanceof FileRequestFailed
            ? cause.message
            : 'The rest of this folder did not arrive. The node may have gone away.',
        )
      })
      .finally(() => setLoadingMore(false))
  }

  const remove = async (targets: FileEntryView[]) => {
    if (targets.length === 0) {
      return
    }
    const confirmed = await askConfirmation({
      title: targets.length === 1 ? 'Delete this entry?' : `Delete ${targets.length} entries?`,
      body: 'Folders are deleted with everything inside them. Nothing here goes to a bin.',
      confirmLabel: 'Delete',
      tone: 'danger',
    })
    if (!confirmed) {
      return
    }
    const gone = await deletePaths(
      serviceId,
      root.id,
      targets.map((entry) => entry.path),
      true,
    )
    clear()
    if (gone > 1) {
      toast.success(`Deleted ${gone} entries.`)
    }
  }

  /** A folder is walked into; a file is edited when it can be and downloaded when it cannot. */
  const open = useCallback(
    (entry: FileEntryView) => {
      if (entry.directory) {
        router.visit(browseHref(serviceId, root.id, entry.path, showHidden))
        return
      }
      if (entry.symlink) {
        toast.info(
          `${entry.name} is a link. The panel reports links and never follows them, so open ` +
            'what it points at directly.',
        )
        return
      }
      if (isEditable(entry, maxEditableBytes)) {
        setOverlay({kind: 'edit', entry})
        return
      }
      toast.info(`${entry.name} is too large to open here. Downloading it instead.`)
      window.location.href = downloadHref(serviceId, root.id, entry.path)
    },
    [serviceId, root.id, showHidden, maxEditableBytes],
  )

  const act = (kind: FileActionKind, targets: FileEntryView[] = selection.entries) => {
    const only = targets[0]
    switch (kind) {
      case 'refresh':
        refresh()
        return
      case 'newFolder':
        setOverlay({kind: 'newFolder'})
        return
      case 'upload':
        setOverlay({kind: 'upload'})
        return
      case 'measure':
        setOverlay({kind: 'measure', path: only?.directory ? only.path : path})
        return
      case 'compress':
        setOverlay({kind: 'compress', paths: targets.map((entry) => entry.path)})
        return
      case 'move':
        setOverlay({kind: 'move', entries: targets})
        return
      case 'delete':
        void remove(targets)
        return
      default:
        break
    }
    if (!only) {
      return
    }
    switch (kind) {
      case 'open':
        open(only)
        return
      case 'edit':
        setOverlay({kind: 'edit', entry: only})
        return
      case 'download':
        window.location.href = downloadHref(serviceId, root.id, only.path)
        return
      case 'rename':
        setOverlay({kind: 'rename', entry: only})
        return
      case 'chmod':
        setOverlay({kind: 'chmod', entry: only})
        return
      case 'extract':
        setOverlay({kind: 'extract', entry: only})
        return
      default:
        return
    }
  }

  /** Only what the current selection allows, so a shortcut cannot do what a button will not. */
  const runIfAllowed = (kind: FileActionKind) => {
    if (states.find((state) => state.kind === kind)?.disabledReason === null) {
      act(kind)
    }
  }

  useFileShortcuts(
    {
      open: () => {
        const at = entries[selection.cursor]
        if (at) {
          open(at)
        }
      },
      up: () => {
        if (path !== '') {
          router.visit(browseHref(serviceId, root.id, parentPath, showHidden))
        }
      },
      rename: () => runIfAllowed('rename'),
      remove: () => runIfAllowed('delete'),
      selectAll: selection.selectAll,
      clear: () => {
        setMenu(null)
        clear()
      },
      moveCursor: selection.moveCursor,
      extendCursor: (delta) => selection.extendTo(Math.max(0, selection.cursor + delta)),
      toggleCursor: () => {
        if (selection.cursor >= 0) {
          selection.toggle(selection.cursor)
        }
      },
      editPath: () => setEditingPath(true),
    },
    overlay.kind === 'none' && menu === null,
  )

  const uploadRefusal = canWrite
    ? 'Uploading is off while this tree cannot be reached.'
    : root.writable
      ? 'You have read access to this organization, so uploading is off.'
      : "This is a static site's releases tree. Its files come from the last build, and anything written here would be replaced by the next deployment."

  return (
    <div className="flex flex-col gap-3">
      <Head title={`Files · ${service.name}`} />
      <ServiceTabs serviceId={serviceId} />

      <PageHeader
        title="Files"
        description={`${root.label}, on the machine holding ${service.name}. There is no SFTP: this is the way in.`}
      />

      <FilePathBar
        serviceId={serviceId}
        roots={roots}
        root={root}
        path={path}
        parentPath={parentPath}
        showHidden={showHidden}
        editing={editingPath}
        onEditingChange={setEditingPath}
      />

      <FileToolbar
        states={states}
        onAction={(kind) => act(kind)}
        onOpenSheet={() => setMenu({anchor: null})}
        selectedCount={selection.count}
        onClearSelection={clear}
        refreshing={refreshing}
      />

      {uploads.busy && overlay.kind !== 'upload' ? (
        <button
          type="button"
          onClick={() => setOverlay({kind: 'upload'})}
          className="flex items-center gap-3 rounded-xl border border-accent-500/40 bg-accent-500/10 px-3 py-2 text-left text-sm text-ink-800 dark:text-ink-100"
        >
          <Spinner />
          <span className="flex-1">
            Uploading {uploads.items.filter((item) => item.status !== 'done').length} file
            {uploads.items.filter((item) => item.status !== 'done').length === 1 ? '' : 's'}
            {uploads.percent === null ? '' : ` · ${uploads.percent}%`}
          </span>
          <span className="text-xs text-ink-600 dark:text-ink-300">Show</span>
        </button>
      ) : null}

      {unavailable ? (
        <ErrorState
          title="These files cannot be listed right now"
          description={unavailable}
          onRetry={refresh}
          retryLabel="Try again"
        />
      ) : (
        <Card padded={false}>
          <FileList
            entries={entries}
            total={page.total}
            selection={selection}
            order={order}
            onSort={(key: SortKey) => setOrder((current) => nextOrder(current, key))}
            context={context}
            onOpen={open}
            onAction={act}
            onMenu={(anchor) => setMenu({anchor})}
            hasMore={Boolean(cursor)}
            loadingMore={loadingMore}
            loadError={loadError}
            onLoadMore={loadMore}
            showingHidden={showHidden}
            readOnlyNote={
              canWrite
                ? null
                : 'This tree is read-only for you, so the operations that would change it are switched off.'
            }
          />
        </Card>
      )}

      <div className="px-1">
        <Checkbox
          label="Show dotfiles"
          checked={showHidden}
          onChange={(event) =>
            router.visit(browseHref(serviceId, root.id, path, event.target.checked))
          }
          hint="Hidden by default: what a customer came to see is their own files, and this is one tap."
        />
      </div>

      <FileActionMenu
        open={menu !== null}
        anchor={menu?.anchor ?? null}
        title={
          selection.count === 1
            ? (selection.entries[0]?.name ?? 'This folder')
            : selection.count > 1
              ? `${selection.count} selected`
              : 'This folder'
        }
        states={states}
        onAction={(kind) => act(kind)}
        onClose={() => setMenu(null)}
      />

      <UploadDropOverlay
        onFiles={(files) => {
          uploads.add(files)
          setOverlay({kind: 'upload'})
        }}
        onFolders={(names) =>
          toast.error(
            `${names.join(', ')} ${names.length === 1 ? 'is a folder' : 'are folders'}. ` +
              'Compress it first, upload the archive, then use Extract on it - that keeps the ' +
              'structure and survives a dropped connection, which a folder of loose files ' +
              'would not.',
          )
        }
        disabled={!canWrite || unavailable !== null}
        disabledReason={uploadRefusal}
        destination={path}
      />

      <UploadDialog
        open={overlay.kind === 'upload'}
        onClose={() => setOverlay({kind: 'none'})}
        uploads={uploads}
        destination={path}
        canWrite={canWrite && unavailable === null}
        refusalReason={uploadRefusal}
      />

      <NewFolderDialog
        open={overlay.kind === 'newFolder'}
        onClose={() => setOverlay({kind: 'none'})}
        serviceId={serviceId}
        rootId={root.id}
        path={path}
      />
      <RenameDialog
        entry={overlay.kind === 'rename' ? overlay.entry : null}
        onClose={() => setOverlay({kind: 'none'})}
        serviceId={serviceId}
        rootId={root.id}
      />
      <MoveDialog
        entries={overlay.kind === 'move' ? overlay.entries : []}
        onClose={() => setOverlay({kind: 'none'})}
        serviceId={serviceId}
        rootId={root.id}
        directory={path}
      />
      <PermissionsDialog
        entry={overlay.kind === 'chmod' ? overlay.entry : null}
        onClose={() => setOverlay({kind: 'none'})}
        serviceId={serviceId}
        rootId={root.id}
      />
      <ExtractDialog
        archive={overlay.kind === 'extract' ? overlay.entry : null}
        onClose={() => setOverlay({kind: 'none'})}
        serviceId={serviceId}
        rootId={root.id}
        directory={path}
      />
      <ArchiveDialog
        open={overlay.kind === 'compress'}
        onClose={() => setOverlay({kind: 'none'})}
        serviceId={serviceId}
        rootId={root.id}
        directory={path}
        paths={overlay.kind === 'compress' ? overlay.paths : []}
      />
      <DirectorySizeDialog
        serviceId={serviceId}
        rootId={root.id}
        path={overlay.kind === 'measure' ? overlay.path : null}
        onClose={() => setOverlay({kind: 'none'})}
      />

      {overlay.kind === 'edit' ? (
        <Suspense
          fallback={
            <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink-950/40">
              <span className="flex items-center gap-2 rounded-lg bg-white px-4 py-3 text-sm shadow-lg dark:bg-ink-900">
                <Spinner />
                Opening the editor
              </span>
            </div>
          }
        >
          <FileEditor
            entry={overlay.entry}
            onClose={() => setOverlay({kind: 'none'})}
            serviceId={serviceId}
            rootId={root.id}
            canWrite={canWrite}
          />
        </Suspense>
      ) : null}
    </div>
  )
}
