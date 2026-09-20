import {useEffect, useRef, useState} from 'react'

import {t} from '@/i18n'
import {cx} from '@/shell'

/**
 * Dropping files anywhere on the page.
 *
 * The target is the window, not a rectangle. Somebody dragging a file at a folder listing
 * aims at the listing; a dashed box below it is a target that has to be hit, and a target
 * that has to be hit is a target that gets missed. It also means the drop zone costs no
 * layout at all until there is something being dragged - which is the whole complaint that
 * started this rewrite, because a permanent dashed box was occupying the top third of the
 * screen for a gesture that happens a few times a day.
 *
 * A refusal is shown rather than ignored. Dragging onto a read-only tree used to do
 * nothing at all, which reads as a broken page; the overlay turns red and says why, and
 * the drop is discarded.
 *
 * A dropped *folder* is walked: the entries are handed to the caller, which recurses into
 * them and uploads every file beneath with its relative path, so the tree is reconstructed
 * on the node. Empty directories are not created - they have no files to carry - which is
 * the same trade every browser-based uploader makes.
 */
export function UploadDropOverlay({
  onFiles,
  onFolderEntries,
  disabled,
  disabledReason,
  destination,
}: {
  onFiles: (files: File[]) => void
  /** The directory entries a drop included, for the caller to walk and upload. */
  onFolderEntries: (entries: FileSystemEntry[]) => void
  disabled: boolean
  disabledReason: string
  /** Where the files would land, shown so a drag onto the wrong folder is visible. */
  destination: string
}) {
  const [dragging, setDragging] = useState(false)
  const depth = useRef(0)
  // The listeners outlive the render that created them, and the destination folder changes
  // underneath them as the customer navigates.
  const sink = useRef({onFiles, onFolderEntries, disabled})
  sink.current = {onFiles, onFolderEntries, disabled}

  useEffect(() => {
    const onDragEnter = (event: DragEvent) => {
      if (!carriesFiles(event)) {
        return
      }
      event.preventDefault()
      depth.current += 1
      setDragging(true)
    }
    const onDragOver = (event: DragEvent) => {
      if (!carriesFiles(event)) {
        return
      }
      // Without this the browser navigates to the file instead, which loses the page and
      // any upload already running on it.
      event.preventDefault()
      if (event.dataTransfer) {
        event.dataTransfer.dropEffect = sink.current.disabled ? 'none' : 'copy'
      }
    }
    const onDragLeave = (event: DragEvent) => {
      if (
        event.relatedTarget === null ||
        (event.relatedTarget instanceof Node && !document.documentElement.contains(event.relatedTarget))
      ) {
        depth.current = 0
        setDragging(false)
      } else {
        depth.current = Math.max(0, depth.current - 1)
        if (depth.current === 0) {
          setDragging(false)
        }
      }
    }
    const onDrop = (event: DragEvent) => {
      if (!carriesFiles(event)) {
        return
      }
      event.preventDefault()
      depth.current = 0
      setDragging(false)
      if (sink.current.disabled || !event.dataTransfer) {
        return
      }
      const {files, folders} = split(event.dataTransfer)
      if (folders.length > 0) {
        sink.current.onFolderEntries(folders)
      }
      if (files.length > 0) {
        sink.current.onFiles(files)
      }
    }

    window.addEventListener('dragenter', onDragEnter)
    window.addEventListener('dragover', onDragOver)
    window.addEventListener('dragleave', onDragLeave)
    window.addEventListener('drop', onDrop)
    return () => {
      window.removeEventListener('dragenter', onDragEnter)
      window.removeEventListener('dragover', onDragOver)
      window.removeEventListener('dragleave', onDragLeave)
      window.removeEventListener('drop', onDrop)
    }
  }, [])

  if (!dragging) {
    return null
  }

  return (
    <div
      // Pointer events off: the overlay must not become the drop target itself, or the
      // dragleave bookkeeping above sees an enter it never sees the leave for.
      className="pointer-events-none fixed inset-0 z-50 flex items-center justify-center p-6"
      aria-hidden="true"
    >
      <div className="absolute inset-0 bg-ink-950/40 backdrop-blur-[2px]" />
      <div
        className={cx(
          'relative flex max-w-md flex-col items-center gap-2 rounded-2xl border-2 border-dashed',
          'bg-white px-8 py-10 text-center shadow-xl dark:bg-ink-900',
          disabled ? 'border-failed' : 'border-accent-500',
        )}
      >
        <p className="text-base font-semibold text-ink-900 dark:text-ink-100">
          {disabled ? t('files.drop.refused_title') : t('files.drop.ready_title')}
        </p>
        <p className="text-sm text-ink-600 dark:text-ink-400">
          {disabled
            ? disabledReason
            : t('files.drop.ready_desc', {
                destination: destination === '' ? t('files.drop.top_of_tree') : destination,
              })}
        </p>
      </div>
    </div>
  )
}

/** Whether this drag is carrying files rather than selected text or a link. */
function carriesFiles(event: DragEvent): boolean {
  const types = event.dataTransfer?.types
  return types !== undefined && Array.from(types).includes('Files')
}

/**
 * The files, and the directory entries a drop included.
 *
 * `webkitGetAsEntry` is the only way to tell the two apart before reading them: a dropped
 * folder arrives in `dataTransfer.files` as a zero-byte entry with the folder's name, and
 * uploading that produces an empty file with no error anywhere. The directory entries are
 * returned as `FileSystemEntry` objects so the caller can recurse into them and collect the
 * files with their relative paths.
 */
function split(transfer: DataTransfer): {files: File[]; folders: FileSystemEntry[]} {
  const files: File[] = []
  const folders: FileSystemEntry[] = []

  if (transfer.items && transfer.items.length > 0) {
    for (const item of Array.from(transfer.items)) {
      if (item.kind !== 'file') {
        continue
      }
      const entry = item.webkitGetAsEntry?.()
      const file = item.getAsFile()
      if (entry?.isDirectory) {
        folders.push(entry)
      } else if (file) {
        files.push(file)
      }
    }
    return {files, folders}
  }

  return {files: Array.from(transfer.files), folders}
}
