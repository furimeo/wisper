import {useRef} from 'react'

import {t} from '@/i18n'
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

function getStatusLabel(status: UploadStatus): string {
  switch (status) {
    case 'queued':
      return t('files.upload.status_waiting')
    case 'uploading':
      return t('files.upload.status_uploading')
    case 'interrupted':
      return t('files.upload.status_interrupted')
    case 'failed':
      return t('files.upload.status_refused')
    case 'done':
      return t('files.upload.status_uploaded')
    case 'cancelled':
      return t('files.upload.status_cancelled')
  }
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
  const folderPicker = useRef<HTMLInputElement>(null)
  const finished = uploads.items.filter(
    (item) => item.status === 'done' || item.status === 'cancelled',
  )

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t('files.upload.modal_title')}
      description={
        canWrite
          ? t('files.upload.into_desc', {
              destination: destination === '' ? t('files.upload.top_of_tree') : destination,
            })
          : undefined
      }
      size="lg"
      footer={
        <>
          {finished.length > 0 ? (
            <Button variant="secondary" onClick={uploads.clearFinished} block>
              {t('files.upload.clear_finished')}
            </Button>
          ) : null}
          <Button onClick={onClose} block>
            {uploads.busy ? t('files.upload.keep_background') : t('files.upload.close')}
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
            <input
              ref={folderPicker}
              type="file"
              // @ts-expect-error -- webkitdirectory is a non-standard attribute that lets
              // the picker select a folder; the DOM property exists at runtime.
              webkitdirectory=""
              directory=""
              multiple
              className="hidden"
              onChange={(event) => {
                const chosen = event.target.files
                if (chosen && chosen.length > 0) {
                  // Files from a directory picker carry webkitRelativePath, e.g.
                  // "myproject/src/main.go". The folder's name is included, so the
                  // tree is reconstructed under the current directory.
                  uploads.addWithPaths(
                    Array.from(chosen).map((file) => ({
                      file,
                      relativePath: (file as File & {webkitRelativePath: string}).webkitRelativePath,
                    })),
                  )
                }
                event.target.value = ''
              }}
            />
            <FileActionIcon kind="upload" className="size-6 text-ink-400" />
            <p className="text-sm text-ink-600 dark:text-ink-400">
              {t('files.upload.drop_or_pick')}
            </p>
            <div className="flex flex-wrap items-center justify-center gap-2">
              <Button variant="secondary" onClick={() => picker.current?.click()}>
                {t('files.upload.choose_files')}
              </Button>
              <Button variant="secondary" onClick={() => folderPicker.current?.click()}>
                {t('files.upload.choose_folder')}
              </Button>
            </div>
            <p className="max-w-prose text-xs text-ink-500 dark:text-ink-400">
              {t('files.upload.chunked_notice')}
            </p>
            <p className="max-w-prose text-xs text-ink-500 dark:text-ink-400">
              {t('files.page.folder_upload_hint')}
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
          {getStatusLabel(item.status)}
        </Badge>
      </div>

      {item.status === 'done' ? null : (
        <div
          className="h-1.5 w-full overflow-hidden rounded-full bg-ink-200 dark:bg-ink-800"
          role="img"
          aria-label={t('files.upload.progress_aria', {percent, name: item.name})}
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
          {t('files.upload.bytes_progress', {
            sent: formatBytes(item.sentBytes),
            total: formatBytes(item.totalBytes),
          })}
          {item.resumed && running ? t('files.upload.resumed_note') : ''}
        </p>
        <div className="flex items-center gap-2">
          {running ? (
            <Button variant="ghost" size="sm" onClick={() => uploads.cancel(item.id)}>
              {t('files.upload.cancel')}
            </Button>
          ) : null}
          {item.status === 'interrupted' || item.status === 'failed' ? (
            <Button variant="secondary" size="sm" onClick={() => uploads.resume(item.id)}>
              {t('files.upload.resume')}
            </Button>
          ) : null}
          {running ? null : (
            <Button variant="ghost" size="sm" onClick={() => uploads.dismiss(item.id)}>
              {t('files.upload.dismiss')}
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
