import {useEffect, useRef, useState} from 'react'

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
 * A dropped *folder* is refused with the way to do it instead. Directory upload would mean
 * creating the tree as it goes and there is no endpoint that does that, so rather than
 * uploading the loose files and quietly dropping the structure, the panel says to compress
 * it and offers the unzip that finishes the job.
 */
export function UploadDropOverlay({
  onFiles,
  onFolders,
  disabled,
  disabledReason,
  destination,
}: {
  onFiles: (files: File[]) => void
  /** The names of anything dropped that was a directory, for the caller to explain. */
  onFolders: (names: string[]) => void
  disabled: boolean
  disabledReason: string
  /** Where the files would land, shown so a drag onto the wrong folder is visible. */
  destination: string
}) {
  const [dragging, setDragging] = useState(false)
  const depth = useRef(0)
  // The listeners outlive the render that created them, and the destination folder changes
  // underneath them as the customer navigates.
  const sink = useRef({onFiles, onFolders, disabled})
  sink.current = {onFiles, onFolders, disabled}

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
    const onDragLeave = () => {
      depth.current = Math.max(0, depth.current - 1)
      if (depth.current === 0) {
        setDragging(false)
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
        sink.current.onFolders(folders)
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
          {disabled ? 'These cannot be uploaded here' : 'Drop to upload'}
        </p>
        <p className="text-sm text-ink-600 dark:text-ink-400">
          {disabled
            ? disabledReason
            : `They go into ${destination === '' ? 'the top of this tree' : destination}, and carry on by themselves if the connection drops.`}
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
 * The files, and the names of anything that was a directory.
 *
 * `webkitGetAsEntry` is the only way to tell the two apart before reading them: a dropped
 * folder arrives in `dataTransfer.files` as a zero-byte entry with the folder's name, and
 * uploading that produces an empty file with no error anywhere.
 */
function split(transfer: DataTransfer): {files: File[]; folders: string[]} {
  const files: File[] = []
  const folders: string[] = []

  if (transfer.items && transfer.items.length > 0) {
    for (const item of Array.from(transfer.items)) {
      if (item.kind !== 'file') {
        continue
      }
      const entry = item.webkitGetAsEntry?.()
      const file = item.getAsFile()
      if (entry?.isDirectory) {
        folders.push(entry.name)
      } else if (file) {
        files.push(file)
      }
    }
    return {files, folders}
  }

  return {files: Array.from(transfer.files), folders}
}
