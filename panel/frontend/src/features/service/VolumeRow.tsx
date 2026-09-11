import {Badge, ByteSize, Icon, RelativeTime, cx} from '@/shell'

import type {Volume} from './serviceTypes'

/**
 * One disk attached to a service.
 *
 * Two numbers matter and they come from opposite directions: `sizeBytes` is the quota the
 * panel published, and `usedBytes` is what the node measured. The second is null until a
 * node has reported, and that is drawn as "not measured yet" rather than as zero - a bar
 * sitting empty because nothing has been counted looks exactly like a bar sitting empty
 * because the disk is free, and only one of those means the volume is fine.
 *
 * `lastError` is shown in full when there is one. A volume the node could not create is
 * the single most useful sentence on this screen, and hiding it behind a detail view is
 * how a customer concludes the platform silently did nothing.
 */
export function VolumeRow({
  volume,
  onOpen,
}: {
  volume: Volume
  /** Absent for a viewer: there is nothing behind the row for somebody who cannot write. */
  onOpen?: () => void
}) {
  const used = volume.usedBytes
  const measured = used !== null
  const share = used !== null && volume.sizeBytes > 0 ? used / volume.sizeBytes : 0
  const percent = Math.round(Math.min(1, share) * 100)
  const full = share >= 0.9

  const body = (
    <>
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-2">
          <code className="truncate font-mono text-sm font-medium text-ink-900 dark:text-ink-100">
            {volume.mountPath}
          </code>
          {volume.readOnly ? <Badge tone="neutral">Read-only</Badge> : null}
          {volume.backupEnabled ? null : <Badge tone="degraded">No backups</Badge>}
        </span>

        <span className="mt-0.5 block truncate text-xs text-ink-500 dark:text-ink-400">
          {volume.name} · <ByteSize bytes={volume.sizeBytes} />
          {used !== null ? (
            <>
              {' · '}
              <span className={full ? 'text-degraded' : undefined}>
                <ByteSize bytes={used} /> used
              </span>
            </>
          ) : (
            ' · not measured yet'
          )}
        </span>

        <span
          className="mt-1.5 block h-1.5 w-full overflow-hidden rounded-full bg-ink-200 dark:bg-ink-800"
          role="img"
          aria-label={
            measured
              ? `${volume.name} is ${percent}% full`
              : `${volume.name} has not been measured by a node yet`
          }
        >
          <span
            className={cx(
              'block h-full rounded-full',
              measured ? (full ? 'bg-degraded' : 'bg-accent-500') : 'bg-transparent',
            )}
            style={{width: used === null ? '0%' : `${Math.max(percent, used > 0 ? 2 : 0)}%`}}
          />
        </span>

        {volume.usedBytesMeasuredAt ? (
          <span className="mt-1 block text-xs text-ink-500 dark:text-ink-400">
            Measured <RelativeTime at={volume.usedBytesMeasuredAt} />
            {volume.inodeCount === null ? null : ` · ${volume.inodeCount.toLocaleString()} files`}
          </span>
        ) : null}

        {volume.lastError ? (
          <span className="mt-1 block text-xs leading-relaxed text-failed">
            {volume.lastError}
          </span>
        ) : null}
      </span>

      {onOpen ? <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" /> : null}
    </>
  )

  return (
    <li>
      {onOpen ? (
        <button
          type="button"
          onClick={onOpen}
          className="flex w-full touch-target items-start gap-3 px-4 py-3 text-left transition-colors hover:bg-ink-100 md:px-5 dark:hover:bg-ink-800"
        >
          {body}
        </button>
      ) : (
        <div className="flex w-full touch-target items-start gap-3 px-4 py-3 md:px-5">{body}</div>
      )}
    </li>
  )
}
