import {t} from '@/i18n'
import {Badge, ByteSize, CopyButton, Icon, RelativeTime, cx} from '@/shell'

import type {Volume} from './serviceTypes'

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
          <CopyButton
            value={volume.mountPath}
            size="sm"
            describedAs={t('service.volumes.copy_mount_path')}
          />
          {volume.readOnly ? <Badge tone="neutral">{t('service.volumes.read_only_badge')}</Badge> : null}
          {volume.backupEnabled ? null : <Badge tone="degraded">{t('service.volumes.no_backups_badge')}</Badge>}
        </span>

        <span className="mt-0.5 block truncate text-xs text-ink-500 dark:text-ink-400">
          {volume.name} · <ByteSize bytes={volume.sizeBytes} />
          {used !== null ? (
            <>
              {' · '}
              <span className={full ? 'text-degraded' : undefined}>
                <ByteSize bytes={used} /> {t('service.volumes.used_word')}
              </span>
            </>
          ) : (
            ` · ${t('service.volumes.not_measured_yet')}`
          )}
        </span>

        <span
          className="mt-1.5 block h-1.5 w-full overflow-hidden rounded-full bg-ink-200 dark:bg-ink-800"
          role="img"
          aria-label={
            measured
              ? t('service.volumes.measured_full_aria', {name: volume.name, percent})
              : t('service.volumes.not_measured_aria', {name: volume.name})
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
            {t('service.volumes.measured_at', {time: ''})}
            <RelativeTime at={volume.usedBytesMeasuredAt} />
            {volume.inodeCount === null
              ? null
              : ` · ${t('service.volumes.files_count', {count: volume.inodeCount.toLocaleString()})}`}
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
        <div className="flex w-full touch-target items-start gap-3 px-4 py-3 md:px-5">
          {body}
        </div>
      )}
    </li>
  )
}
