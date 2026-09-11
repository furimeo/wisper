import {useRef} from 'react'

import {Badge, Button, Modal, cx, formatBytes} from '@/shell'
import type {BadgeTone} from '@/shell'

import {FileActionIcon} from './FileActionIcon'
import type {ChunkedUpload} from './useChunkedUpload'
import type {UploadItem, UploadStatus} from './uploadQueue'

const TONES: Record<UploadStatus, BadgeTone> = {
  queued: 'neutral',
  uploading: 'accent',
  interrupted: 'degraded',
  failed: 'failed',
  done: 'running',
  cancelled: 'neutral',
}

const LABELS: Record<UploadStatus, string> = {
  queued: 'Waiting',
  uploading: 'Uploading',
  interrupted: 'Interrupted',
  failed: 'Refused',
  done: 'Uploaded',
  cancelled: 'Cancelled',
}

/**
 * Uploading, as a dialog rather than as a permanent fixture of the page.
 *
 * The drop zone used to be a dashed box occupying the top third of the screen for a
 * gesture that happens a few times a day. It is now here, behind a toolbar button and
 * behind a drop anywhere on the page - which is the same place every other decision in
 * this panel is made and leaves the listing the whole width and height it deserves.
 *
 * Closing it does not stop anything. The queue lives in `uploadQueue.ts`, outside React,
 * so an upload survives this dialog closing, the customer walking into another folder and
 * the page being re-rendered by the redirect after a delete. The toolbar shows the
 * percentage while it is shut; reopening shows the rows again.
 *
 * "Interrupted" and "failed" are drawn differently on purpose. Somebody whose 4G dropped
 * in a lift needs to see that nothing was lost and it is carrying on; somebody whose
 * upload was refused because the volume is full needs the sentence the panel sent and to
 * stop waiting. Rolling both into one red bar teaches people to distrust the bar.
 */
export function UploadDialog({
  open,
  onClose,
  uploads,
  destination,
  canWrite,
  refusalReason,
}: {
  open: boolean
  onClose: () => void
  uploads: ChunkedUpload
  /** Where a newly picked file lands, relative to the root. */
  destination: string
  canWrite: boolean
  /** Why uploading is off, when it is. */
  refusalReason: string
}) {
  const picker = useRef<HTMLInputElement>(null)
  const finished = uploads.items.filter(
    (item) => item.status === 'done' || item.status === 'cancelled',
  )

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Upload"
      description={
        canWrite
          ? `Into ${destination === '' ? 'the top of this tree' : destination}`
          : undefined
      }
      size="lg"
      footer={
        <>
          {finished.length > 0 ? (
            <Button variant="secondary" onClick={uploads.clearFinished} block>
              Clear finished
            </Button>
          ) : null}
          <Button onClick={onClose} block>
            {uploads.busy ? 'Keep uploading in the background' : 'Close'}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {canWrite ? (
          <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-ink-300 px-4 py-6 text-center dark:border-ink-700">
            <input
              ref={picker}
              type="file"
              multiple
              className="hidden"
              onChange={(event) => {
                const chosen = event.target.files
                if (chosen && chosen.length > 0) {
                  uploads.add([...chosen])
                }
                // Reset, or picking the same file twice in a row fires no change event and
                // the second attempt looks like a button that stopped working.
                event.target.value = ''
              }}
            />
            <FileActionIcon kind="upload" className="size-6 text-ink-400" />
            <p className="text-sm text-ink-600 dark:text-ink-400">
              Drop files anywhere on the page, or pick them here.
            </p>
            <Button variant="secondary" onClick={() => picker.current?.click()}>
              Choose files
            </Button>
            <p className="max-w-prose text-xs text-ink-500 dark:text-ink-400">
              Uploads are sent in chunks and carry on by themselves after a dropped
              connection - they resume from where they stopped rather than starting again.
            </p>
          </div>
        ) : (
          <p className="rounded-lg bg-ink-100 px-3 py-2 text-sm text-ink-700 dark:bg-ink-800 dark:text-ink-200">
            {refusalReason}
          </p>
        )}

        {uploads.items.length === 0 ? null : (
          <ul className="divide-y divide-ink-200 rounded-xl border border-ink-200 dark:divide-ink-800 dark:border-ink-800">
            {uploads.items.map((item) => (
              <li key={item.id} className="px-3 py-3">
                <UploadRow item={item} uploads={uploads} />
              </li>
            ))}
          </ul>
        )}
      </div>
    </Modal>
  )
}

function UploadRow({item, uploads}: {item: UploadItem; uploads: ChunkedUpload}) {
  const percent =
    item.totalBytes <= 0
      ? 100
      : Math.min(100, Math.round((item.sentBytes / item.totalBytes) * 100))
  const running = item.status === 'queued' || item.status === 'uploading'

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-medium text-ink-900 dark:text-ink-100">
            {item.name}
          </p>
          <p className="mt-0.5 truncate text-xs text-ink-500 dark:text-ink-400">{item.path}</p>
        </div>
        <Badge tone={TONES[item.status]} dot pulse={item.status === 'uploading'}>
          {LABELS[item.status]}
        </Badge>
      </div>

      {item.status === 'done' ? null : (
        <div
          className="h-1.5 w-full overflow-hidden rounded-full bg-ink-200 dark:bg-ink-800"
          role="img"
          aria-label={`${percent}% of ${item.name} uploaded`}
        >
          <div
            className={cx(
              'h-full',
              item.status === 'failed'
                ? 'bg-failed'
                : item.status === 'interrupted'
                  ? 'bg-degraded'
                  : 'bg-accent-500',
            )}
            style={{width: `${percent}%`}}
          />
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs tabular-nums text-ink-500 dark:text-ink-400">
          {formatBytes(item.sentBytes)} of {formatBytes(item.totalBytes)}
          {item.resumed && running ? ' · carried on from where it stopped' : ''}
        </p>
        <div className="flex items-center gap-2">
          {running ? (
            <Button variant="ghost" size="sm" onClick={() => uploads.cancel(item.id)}>
              Cancel
            </Button>
          ) : null}
          {item.status === 'interrupted' || item.status === 'failed' ? (
            <Button variant="secondary" size="sm" onClick={() => uploads.resume(item.id)}>
              Resume
            </Button>
          ) : null}
          {running ? null : (
            <Button variant="ghost" size="sm" onClick={() => uploads.dismiss(item.id)}>
              Dismiss
            </Button>
          )}
        </div>
      </div>

      {item.message ? (
        <p
          className={
            item.status === 'failed'
              ? 'text-xs text-failed'
              : 'text-xs text-ink-600 dark:text-ink-400'
          }
        >
          {item.message}
        </p>
      ) : null}
    </div>
  )
}
