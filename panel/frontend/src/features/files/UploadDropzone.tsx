import {useEffect, useRef, useState} from 'react'

import {Button, Icon, cx, toast} from '@/shell'

/**
 * Getting files in: dropped anywhere on the page, or picked with a tap.
 *
 * Both, because the two halves of this platform's audience do not overlap. Drag and drop
 * is how somebody at a desk moves twenty files at once and there is no substitute for it;
 * a phone has no drag and drop at all, so the visible control is a button that opens the
 * system picker - which is also where "take a photo" and the files app live.
 *
 * The drop target is the window rather than a rectangle. A customer dragging a file at a
 * folder listing aims at the listing, not at a dashed box below it, and a target that has
 * to be hit is a target that gets missed.
 *
 * A dropped *folder* is refused with the way to do it instead. Directory upload would
 * mean creating the tree as it goes, and there is no endpoint that does that - so rather
 * than uploading the loose files and quietly dropping the structure, the panel says to
 * compress it and offers the unzip that finishes the job.
 */
export function UploadDropzone({
  onFiles,
  disabled,
  disabledReason,
}: {
  onFiles: (files: File[]) => void
  /** True for a viewer, and for a read-only tree. */
  disabled: boolean
  disabledReason: string
}) {
  const picker = useRef<HTMLInputElement | null>(null)
  const [dragging, setDragging] = useState(false)
  const depth = useRef(0)
  // The listeners below outlive the render that created them, and the destination folder
  // changes underneath them as the customer navigates.
  const sink = useRef(onFiles)
  sink.current = onFiles

  useEffect(() => {
    if (disabled) {
      return
    }

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
        event.dataTransfer.dropEffect = 'copy'
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
      accept(event.dataTransfer)
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
  }, [disabled])

  const accept = (transfer: DataTransfer | null) => {
    if (!transfer) {
      return
    }
    const {files, folders} = split(transfer)
    if (folders.length > 0) {
      toast.error(
        `${folders.join(', ')} ${folders.length === 1 ? 'is a folder' : 'are folders'}. ` +
          'Compress it first, upload the archive, then use "Unzip here" on it - that keeps ' +
          'the structure and survives a dropped connection, which a folder of loose files ' +
          'would not.',
      )
    }
    if (files.length > 0) {
      sink.current(files)
    }
  }

  return (
    <div
      className={cx(
        'flex flex-col items-center gap-2 rounded-xl border border-dashed px-4 py-5 text-center',
        dragging
          ? 'border-accent-500 bg-accent-500/10'
          : 'border-ink-300 dark:border-ink-700',
      )}
    >
      <input
        ref={picker}
        type="file"
        multiple
        className="hidden"
        onChange={(event) => {
          const chosen = event.target.files
          if (chosen && chosen.length > 0) {
            onFiles([...chosen])
          }
          // Reset, or picking the same file twice in a row fires no change event and the
          // second attempt looks like a button that stopped working.
          event.target.value = ''
        }}
      />

      {disabled ? (
        <p className="text-sm text-ink-500 dark:text-ink-400">{disabledReason}</p>
      ) : (
        <>
          <Icon name="inbox" className="size-6 text-ink-400" />
          <p className="text-sm text-ink-600 dark:text-ink-400">
            {dragging
              ? 'Drop them anywhere on this page'
              : 'Drop files here, or pick them from your phone.'}
          </p>
          <Button variant="secondary" onClick={() => picker.current?.click()}>
            Choose files
          </Button>
          <p className="text-xs text-ink-500 dark:text-ink-400">
            Uploads carry on by themselves after a dropped connection - they resume from
            where they stopped rather than starting again.
          </p>
        </>
      )}
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
