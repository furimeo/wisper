import {Suspense, lazy} from 'react'

import {Spinner} from '@/shell'

import {ArchiveDialog} from './ArchiveDialog'
import {DirectorySizeDialog} from './DirectorySizeDialog'
import {ExtractDialog} from './ExtractDialog'
import {MoveDialog} from './MoveDialog'
import {NewFolderDialog} from './NewFolderDialog'
import {PermissionsDialog} from './PermissionsDialog'
import {RenameDialog} from './RenameDialog'
import {UploadDialog} from './UploadDialog'
import type {Overlay} from './fileOverlay'
import type {ChunkedUpload} from './useChunkedUpload'

/*
 * CodeMirror is about a third of a megabyte and nobody opening a folder listing has asked
 * for it yet. Splitting it here is what `vite.config.ts` means by "the terminal and the
 * editor are split out by the dynamic imports in their features".
 */
const FileEditor = lazy(() =>
  import('./FileEditor').then((module) => ({default: module.FileEditor})),
)

/**
 * Every dialog this screen can raise, and nothing else.
 *
 * <p>Split out of `FileManagerPage` because it is the one part of that file that holds no
 * state: it reads which overlay is up and closes it, and every other prop is a constant
 * for the folder being looked at. Ten dialogs, each four lines of wiring, is a hundred
 * lines of the page that never change when the page's behaviour does.
 *
 * <p>They render unmounted rather than conditionally for the most part, because each one
 * owns its own form state and a dialog that unmounts on close forgets what was typed into
 * it - which is the wrong behaviour when a rename is refused and the customer wants to
 * adjust one character. The editor is the exception: it is a lazy chunk, so it must not be
 * asked for until somebody opens a file.
 */
export function FileOverlays({
  overlay,
  onClose,
  serviceId,
  rootId,
  path,
  canWrite,
  uploads,
  uploadRefusal,
  unavailable,
}: {
  overlay: Overlay
  onClose: () => void
  serviceId: string
  rootId: string
  path: string
  canWrite: boolean
  uploads: ChunkedUpload
  uploadRefusal: string
  unavailable: boolean
}) {
  return (
    <>
      <UploadDialog
        open={overlay.kind === 'upload'}
        onClose={onClose}
        uploads={uploads}
        destination={path}
        canWrite={canWrite && !unavailable}
        refusalReason={uploadRefusal}
      />

      <NewFolderDialog
        open={overlay.kind === 'newFolder'}
        onClose={onClose}
        serviceId={serviceId}
        rootId={rootId}
        path={path}
      />
      <RenameDialog
        entry={overlay.kind === 'rename' ? overlay.entry : null}
        onClose={onClose}
        serviceId={serviceId}
        rootId={rootId}
      />
      <MoveDialog
        entries={overlay.kind === 'move' ? overlay.entries : []}
        onClose={onClose}
        serviceId={serviceId}
        rootId={rootId}
        directory={path}
      />
      <PermissionsDialog
        entry={overlay.kind === 'chmod' ? overlay.entry : null}
        onClose={onClose}
        serviceId={serviceId}
        rootId={rootId}
      />
      <ExtractDialog
        archive={overlay.kind === 'extract' ? overlay.entry : null}
        onClose={onClose}
        serviceId={serviceId}
        rootId={rootId}
        directory={path}
      />
      <ArchiveDialog
        open={overlay.kind === 'compress'}
        onClose={onClose}
        serviceId={serviceId}
        rootId={rootId}
        directory={path}
        paths={overlay.kind === 'compress' ? overlay.paths : []}
      />
      <DirectorySizeDialog
        serviceId={serviceId}
        rootId={rootId}
        path={overlay.kind === 'measure' ? overlay.path : null}
        onClose={onClose}
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
            onClose={onClose}
            serviceId={serviceId}
            rootId={rootId}
            canWrite={canWrite}
          />
        </Suspense>
      ) : null}
    </>
  )
}
