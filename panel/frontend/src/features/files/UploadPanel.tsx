import {Badge, Button, Card, formatBytes} from '@/shell'
import type {BadgeTone} from '@/shell'

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
 * What is being uploaded, and what happened to the ones that stopped.
 *
 * The panel is the visible half of the resumable protocol, and it exists because
 * "interrupted" has to look different from "failed". A customer whose 4G dropped in a
 * lift needs to see that nothing was lost and that it is carrying on; a customer whose
 * upload was refused because the volume is full needs to see the sentence the panel sent
 * and stop waiting. Rolling both into a red bar teaches people to distrust the bar.
 *
 * A row survives navigation, because the queue does. Walking into another folder to check
 * something while a 400 MB file uploads is normal, and finding the progress gone would
 * read as having cancelled it.
 */
export function UploadPanel({uploads}: {uploads: ChunkedUpload}) {
  if (uploads.items.length === 0) {
    return null
  }

  const finished = uploads.items.filter(
    (item) => item.status === 'done' || item.status === 'cancelled',
  )

  return (
    <Card
      title={uploads.busy ? `Uploading (${uploads.percent ?? 0}%)` : 'Uploads'}
      action={
        finished.length > 0 ? (
          <Button variant="ghost" size="sm" onClick={uploads.clearFinished}>
            Clear finished
          </Button>
        ) : null
      }
      padded={false}
    >
      <ul className="divide-y divide-ink-200 dark:divide-ink-800">
        {uploads.items.map((item) => (
          <li key={item.id} className="px-4 py-3">
            <UploadRow item={item} uploads={uploads} />
          </li>
        ))}
      </ul>
    </Card>
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
          <p className="mt-0.5 truncate text-xs text-ink-500 dark:text-ink-400">
            {item.path}
          </p>
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
            className={
              item.status === 'failed'
                ? 'h-full bg-failed'
                : item.status === 'interrupted'
                  ? 'h-full bg-degraded'
                  : 'h-full bg-accent-500'
            }
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
