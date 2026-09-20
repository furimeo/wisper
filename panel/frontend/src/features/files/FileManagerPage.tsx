import {Head, router, usePage} from '@inertiajs/react'
import {useCallback, useEffect, useMemo, useState} from 'react'

import {
  Card,
  ErrorState,
  PageHeader,
  Spinner,
  askConfirmation,
  toast,
} from '@/shell'
import {t} from '@/i18n'

import {ServiceTabs} from '@/features/service/ServiceTabs'
import type {ServiceLocation} from '@/features/service/serviceTypes'

import {FileActionMenu} from './FileActionMenu'
import type {MenuAnchor} from './FileActionMenu'
import {FileChromeBar} from './FileChromeBar'
import {FileList} from './FileList'
import {FileOverlays} from './FileOverlays'
import {FileSelectionBar} from './FileSelectionBar'
import {FileStatusBar} from './FileStatusBar'
import {NoStorageState} from './NoStorageState'
import {UploadDropOverlay} from './UploadDropOverlay'
import {deletePaths} from './deletePaths'
import {actionStates} from './fileActions'
import type {FileActionKind} from './fileActions'
import type {Overlay} from './fileOverlay'
import {isEditable} from './fileKinds'
import {FileRequestFailed, browseHref, downloadHref, listDirectory} from './fileRequests'
import {useFileSelection} from './fileSelection'
import {DEFAULT_SORT, nextOrder, sortEntries} from './fileSorting'
import type {SortKey} from './fileSorting'
import type {DirectoryPage, FileEntryView, FileRootRef} from './fileTypes'
import {useChunkedUpload} from './useChunkedUpload'
import {useFileShortcuts} from './useFileShortcuts'
import {walkEntries} from './walkEntry'

type FileManagerProps = {
  service: ServiceLocation
  roots: FileRootRef[]
  root: FileRootRef | null
  path: string
  parentPath: string
  canWrite: boolean
  showHidden: boolean
  chunkSize: number
  maxEditableBytes: number
  page: DirectoryPage
  unavailable: string | null
  hasStorage?: boolean
}

/**
 * `GET /services/{serviceId}/files` - the whole of a customer's access to their disk.
 *
 * There is no SSH, no SFTP and no WebDAV in wisper, so this screen is not a convenience
 * next to a shell; it is the only way in (design §8.2). It is therefore shaped like a file
 * manager and not like a form with a list attached: a path bar, two create actions, a list
 * that sorts and multi-selects, a right-click menu, a keyboard that can drive all of it,
 * and an editor that fills the window.
 *
 * The chrome is deliberately thin. An earlier pass put all thirteen operations on a
 * permanent toolbar and greyed out the ones that did not apply, which read as a control
 * panel with a list underneath. Actions that need a target now live where the target is:
 * `FileActionMenu` on a row, `FileSelectionBar` once several are picked.
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
 * **The selection speaks in list indices.** Everything below the path bar shares one sorted
 * array, so a shift-click range, the keyboard cursor and the right-click menu all mean the
 * same rows. Sorting is applied here rather than at the node, which is honest only because
 * `FileList` says so when a directory has more pages.
 *
 * Over three hundred lines, deliberately (AGENTS.md §3.2). What is left after the list, the
 * rows, the toolbar, the selection bar, the menu, the selection, the sorting, the shortcuts,
 * the upload engine,
 * the editor and each dialog were moved out is this screen's own state and the switch that
 * routes an action to the right overlay - and splitting *that* means a dozen pieces of
 * state and their setters crossing a component boundary, which is more code in two files
 * than it is in one.
 */
export default function FileManagerPage() {
  const props = usePage<FileManagerProps>().props
  if (props.hasStorage === false || !props.root) {
    return <NoStorageState service={props.service} />
  }
  return <FileManagerBrowser {...props} root={props.root} />
}

function FileManagerBrowser({
  service,
  roots,
  root,
  path,
  parentPath,
  canWrite,
  showHidden,
  maxEditableBytes,
  page,
  unavailable,
}: FileManagerProps & {root: FileRootRef}) {
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
            : t('files.page.load_more_error'),
        )
      })
      .finally(() => setLoadingMore(false))
  }

  const remove = async (targets: FileEntryView[]) => {
    if (targets.length === 0) {
      return
    }
    const confirmed = await askConfirmation({
      title:
        targets.length === 1
          ? t('files.page.delete_confirm_title_one')
          : t('files.page.delete_confirm_title_many', {count: targets.length}),
      body: t('files.page.delete_confirm_body'),
      confirmLabel: t('files.page.delete_confirm_button'),
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
      toast.success(t('files.page.deleted_toast', {count: gone}))
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
        toast.info(t('files.page.symlink_notice', {name: entry.name}))
        return
      }
      if (isEditable(entry, maxEditableBytes)) {
        setOverlay({kind: 'edit', entry})
        return
      }
      toast.info(t('files.page.too_large_notice', {name: entry.name}))
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
      case 'newFile':
        setOverlay({kind: 'newFile'})
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
    ? t('files.page.upload_refusal_unavailable')
    : root.writable
      ? t('files.page.upload_refusal_readonly')
      : t('files.page.upload_refusal_static_site')

  const pendingUploads = uploads.items.filter((item) => item.status !== 'done')
  // Whether anything is actively transferring, vs. everything parked on interrupted.
  // The bar's sentence follows this: "Uploading N files" while bytes are moving,
  // "N files waiting to resume" once a flaky connection has stalled every one.
  const transferring = uploads.items.some(
    (item) => item.status === 'queued' || item.status === 'uploading',
  )

  return (
    <div className="flex flex-col gap-3">
      <Head title={t('files.page.head_title', {service: service.name})} />
      <ServiceTabs serviceId={serviceId} />

      <PageHeader
        title={t('files.page.title')}
        description={t('files.page.page_desc', {root: root.label, service: service.name})}
      />

      <Card padded={false} className="overflow-hidden shadow-sm">
        <FileChromeBar
          serviceId={serviceId}
          roots={roots}
          root={root}
          path={path}
          parentPath={parentPath}
          showHidden={showHidden}
          onShowHiddenChange={(checked) =>
            router.visit(browseHref(serviceId, root.id, path, checked))
          }
          states={states}
          onAction={(kind) => act(kind)}
          refreshing={refreshing}
          editing={editingPath}
          onEditingChange={setEditingPath}
        />

        <FileSelectionBar
          states={states}
          count={selection.count}
          onAction={(kind) => act(kind)}
          onClear={clear}
        />

        {uploads.busy && overlay.kind !== 'upload' ? (
          <button
            type="button"
            onClick={() => setOverlay({kind: 'upload'})}
            className={
              transferring
                ? 'flex items-center gap-3 border-b border-accent-500/40 bg-accent-500/10 px-3 py-2 text-left text-sm text-ink-800 dark:text-ink-100'
                : 'flex items-center gap-3 border-b border-degraded/40 bg-degraded/10 px-3 py-2 text-left text-sm text-ink-800 dark:text-ink-100'
            }
          >
            {transferring ? <Spinner /> : null}
            <span className="flex-1">
              {transferring
                ? t('files.page.uploading_bar', {
                    count: pendingUploads.length,
                    percent: uploads.percent === null ? '' : ` · ${uploads.percent}%`,
                  })
                : t('files.page.waiting_bar', {
                    count: pendingUploads.length,
                    percent: uploads.percent === null ? '' : ` · ${uploads.percent}%`,
                  })}
            </span>
            <span className="text-xs text-ink-600 dark:text-ink-300">
              {t('files.page.uploading_bar_show')}
            </span>
          </button>
        ) : null}

        {unavailable ? (
          <div className="p-6">
            <ErrorState
              title={t('files.page.error_title')}
              description={unavailable}
              onRetry={refresh}
              retryLabel={t('files.page.retry_label')}
            />
          </div>
        ) : (
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
            readOnlyNote={canWrite ? null : t('files.page.read_only_note')}
          />
        )}

        <FileStatusBar entries={entries} selected={selection.entries} />
      </Card>

      <FileActionMenu
        open={menu !== null}
        anchor={menu?.anchor ?? null}
        title={
          selection.count === 1
            ? (selection.entries[0]?.name ?? t('files.menu.this_folder'))
            : selection.count > 1
              ? t('files.menu.selected_count', {count: selection.count})
              : t('files.menu.this_folder')
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
        onFolderEntries={(entries) => {
          // Walk the dropped folder(s) and enqueue every file beneath with its relative
          // path. The node reconstructs the directory tree at completion, so a dropped
          // `myproject/` becomes `/current/myproject/...` on the other side. The walk is
          // async (the entry API is callback-based), so we open the dialog now and let the
          // rows appear as they are discovered.
          setOverlay({kind: 'upload'})
          void walkEntries(entries)
            .then((files) => {
              if (files.length === 0) {
                toast.info(t('files.page.folder_empty_notice'))
                return
              }
              uploads.addWithPaths(files)
            })
            .catch(() => {
              toast.error(t('files.page.folder_read_error'))
            })
        }}
        disabled={!canWrite || unavailable !== null}
        disabledReason={uploadRefusal}
        destination={path}
      />

      <FileOverlays
        overlay={overlay}
        onClose={() => setOverlay({kind: 'none'})}
        serviceId={serviceId}
        rootId={root.id}
        path={path}
        canWrite={canWrite}
        uploads={uploads}
        uploadRefusal={uploadRefusal}
        unavailable={unavailable !== null}
      />
    </div>
  )
}
