import {Head, router, usePage} from '@inertiajs/react'
import {Suspense, lazy, useCallback, useEffect, useState} from 'react'

import {
  Button,
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
import {FileList} from './FileList'
import {FilePathBar} from './FilePathBar'
import {NewFolderDialog} from './NewFolderDialog'
import {PermissionsDialog} from './PermissionsDialog'
import {RenameDialog} from './RenameDialog'
import {SelectionBar} from './SelectionBar'
import {UploadDropzone} from './UploadDropzone'
import {UploadPanel} from './UploadPanel'
import {deletePaths} from './deletePaths'
import type {FileActionKind} from './fileActions'
import {isEditable} from './fileKinds'
import {FileRequestFailed, browseHref, downloadHref, listDirectory} from './fileRequests'
import type {DirectoryPage, FileEntryView, FileRootRef} from './fileTypes'
import {useChunkedUpload} from './useChunkedUpload'

/*
 * CodeMirror is about a third of a megabyte and nobody opening a folder listing has asked
 * for it yet. Splitting it here is what `vite.config.ts` means by "the terminal and the
 * editor are split out by the dynamic imports in their features".
 */
const FileEditor = lazy(() =>
  import('./FileEditor').then((module) => ({default: module.FileEditor})),
)

/**
 * `GET /services/{serviceId}/files` - the whole of a customer's access to their disk.
 *
 * There is no SSH, no SFTP and no WebDAV in wisper, so this screen is not a convenience
 * next to a shell; it is the only way in (design §8.2). Everything that implies is here:
 * browsing, uploading over a connection that drops, downloading, renaming, moving,
 * deleting, permissions, compressing, unpacking, measuring, and editing a file in place.
 *
 * Three things are worth knowing before changing it.
 *
 * **`unavailable` is a real state and is not "no files".** A service with no volumes, a
 * service that has never been started and a node that cannot be reached all produce an
 * empty listing, and the controller sends the sentence that tells them apart. Rendering
 * an empty folder instead would tell a customer their files are gone.
 *
 * **The first page comes in the props.** A page that arrives empty and then fetches shows
 * a spinner on every navigation, which on mobile data is most of the experience. Later
 * pages are `fetch`, because scrolling is not a navigation.
 *
 * **Uploads outlive this component.** They live in `uploadQueue.ts`, so walking into
 * another folder while a 400 MB file goes up does not cancel it.
 *
 * Over three hundred lines, deliberately (AGENTS.md §3.2). What is left after the list,
 * the rows, the upload engine, the editor and each dialog were moved out is the screen's
 * own state and the switch that routes an action to the right overlay - and splitting
 * *that* means seven pieces of state and their setters crossing a component boundary,
 * which is more code in two files than it is in one.
 */
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

export default function FileManagerPage() {
  const props = usePage<FileManagerProps>().props
  const {service, roots, root, path, canWrite, showHidden, maxEditableBytes, page, unavailable} =
    props
  const serviceId = service.serviceId

  const [entries, setEntries] = useState<FileEntryView[]>(page.entries)
  const [cursor, setCursor] = useState<string | null>(page.nextCursor)
  const [loadingMore, setLoadingMore] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set())
  const [deleting, setDeleting] = useState(false)

  const [editing, setEditing] = useState<FileEntryView | null>(null)
  const [renaming, setRenaming] = useState<FileEntryView | null>(null)
  const [permissionsFor, setPermissionsFor] = useState<FileEntryView | null>(null)
  const [extracting, setExtracting] = useState<FileEntryView | null>(null)
  const [measuring, setMeasuring] = useState<string | null>(null)
  const [newFolder, setNewFolder] = useState(false)
  const [archiving, setArchiving] = useState<string[] | null>(null)

  // A new server response - a navigation, or the redirect after a write - replaces
  // whatever "load more" had appended. Keyed on the prop object, which Inertia hands over
  // fresh on every visit.
  useEffect(() => {
    setEntries(page.entries)
    setCursor(page.nextCursor)
    setLoadError(null)
    setSelected(new Set())
  }, [page])

  const refresh = useCallback(() => {
    router.reload({only: ['page']})
  }, [])

  const uploads = useChunkedUpload(serviceId, root.id, path, refresh)

  const loadMore = () => {
    if (!cursor) {
      return
    }
    setLoadingMore(true)
    setLoadError(null)
    listDirectory(serviceId, root.id, path, cursor, showHidden)
      .then((next) => {
        setEntries((current) => [...current, ...next.entries])
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

  const toggle = (entry: FileEntryView) => {
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(entry.path)) {
        next.delete(entry.path)
      } else {
        next.add(entry.path)
      }
      return next
    })
  }

  const toggleAll = () => {
    setSelected((current) =>
      current.size === entries.length ? new Set() : new Set(entries.map((entry) => entry.path)),
    )
  }

  const remove = async (paths: string[]) => {
    if (paths.length === 0) {
      return
    }
    const confirmed = await askConfirmation({
      title: paths.length === 1 ? 'Delete this entry?' : `Delete ${paths.length} entries?`,
      body: 'Folders are deleted with everything inside them. Nothing here goes to a bin.',
      confirmLabel: 'Delete',
      tone: 'danger',
    })
    if (!confirmed) {
      return
    }
    setDeleting(true)
    const gone = await deletePaths(serviceId, root.id, paths, true)
    setDeleting(false)
    setSelected(new Set())
    if (gone > 1) {
      toast.success(`Deleted ${gone} entries.`)
    }
  }

  const act = (kind: FileActionKind, entry: FileEntryView) => {
    switch (kind) {
      case 'open':
        router.visit(browseHref(serviceId, root.id, entry.path, showHidden))
        return
      case 'edit':
        if (isEditable(entry, maxEditableBytes)) {
          setEditing(entry)
        } else if (entry.symlink) {
          toast.info(
            `${entry.name} is a link. The panel reports links and never follows them, so ` +
              'open what it points at directly.',
          )
        } else {
          toast.info(`${entry.name} is too large to open here. Downloading it instead.`)
          window.location.href = downloadHref(serviceId, root.id, entry.path)
        }
        return
      case 'download':
        window.location.href = downloadHref(serviceId, root.id, entry.path)
        return
      case 'rename':
        setRenaming(entry)
        return
      case 'chmod':
        setPermissionsFor(entry)
        return
      case 'extract':
        setExtracting(entry)
        return
      case 'compress':
        setArchiving([entry.path])
        return
      case 'measure':
        setMeasuring(entry.path)
        return
      case 'delete':
        void remove([entry.path])
        return
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Head title={`Files · ${service.name}`} />
      <ServiceTabs serviceId={serviceId} />

      <PageHeader
        title="Files"
        description={`${root.label}, on the machine holding ${service.name}. There is no SFTP: this is the way in.`}
        actions={
          <>
            <Button variant="secondary" onClick={() => setMeasuring(path)}>
              Folder size
            </Button>
            {canWrite ? <Button onClick={() => setNewFolder(true)}>New folder</Button> : null}
          </>
        }
      />

      <FilePathBar
        serviceId={serviceId}
        roots={roots}
        root={root}
        path={path}
        showHidden={showHidden}
      />

      {unavailable ? (
        <ErrorState
          title="These files cannot be listed right now"
          description={unavailable}
          onRetry={refresh}
          retryLabel="Try again"
        />
      ) : null}

      <UploadDropzone
        onFiles={uploads.add}
        disabled={!canWrite || unavailable !== null}
        disabledReason={
          canWrite
            ? 'Uploading is off while this tree cannot be reached.'
            : root.writable
              ? 'You have read access to this organization, so uploading is off.'
              : "This is a static site's releases tree. Its files come from the last build, and anything written here would be replaced by the next deployment."
        }
      />

      <UploadPanel uploads={uploads} />

      <Card padded={false}>
        <SelectionBar
          count={selected.size}
          canWrite={canWrite}
          busy={deleting}
          onCompress={() => setArchiving([...selected])}
          onDelete={() => void remove([...selected])}
          onClear={() => setSelected(new Set())}
        />
        <FileList
          entries={entries}
          total={page.total}
          context={{canWrite, maxEditableBytes}}
          selected={selected}
          onToggleSelected={toggle}
          onToggleAll={toggleAll}
          onAction={act}
          hasMore={Boolean(cursor)}
          loadingMore={loadingMore}
          loadError={loadError}
          onLoadMore={loadMore}
          showingHidden={showHidden}
        />
      </Card>

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

      <NewFolderDialog
        open={newFolder}
        onClose={() => setNewFolder(false)}
        serviceId={serviceId}
        rootId={root.id}
        path={path}
      />
      <RenameDialog
        entry={renaming}
        onClose={() => setRenaming(null)}
        serviceId={serviceId}
        rootId={root.id}
      />
      <PermissionsDialog
        entry={permissionsFor}
        onClose={() => setPermissionsFor(null)}
        serviceId={serviceId}
        rootId={root.id}
      />
      <ExtractDialog
        archive={extracting}
        onClose={() => setExtracting(null)}
        serviceId={serviceId}
        rootId={root.id}
        directory={path}
      />
      <ArchiveDialog
        open={archiving !== null && archiving.length > 0}
        onClose={() => setArchiving(null)}
        serviceId={serviceId}
        rootId={root.id}
        directory={path}
        paths={archiving ?? []}
      />
      <DirectorySizeDialog
        serviceId={serviceId}
        rootId={root.id}
        path={measuring}
        onClose={() => setMeasuring(null)}
      />

      {editing ? (
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
            entry={editing}
            onClose={() => setEditing(null)}
            serviceId={serviceId}
            rootId={root.id}
            canWrite={canWrite}
          />
        </Suspense>
      ) : null}
    </div>
  )
}
