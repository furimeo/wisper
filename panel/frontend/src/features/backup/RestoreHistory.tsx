import {Link} from '@inertiajs/react'

import {t} from '@/i18n'
import {Badge, ByteSize, Card, EmptyState, Icon, RelativeTime} from '@/shell'

import type {RestoreRunView} from './backupTypes'
import {restoreModeLabel, restoreStateLabel, restoreStateTone} from './backupVocabulary'

/**
 * Every restore anybody has started here lately, both kinds.
 *
 * A verify run is a restore that overwrites nothing, and it is listed alongside the real
 * ones on purpose: this list is the evidence that the backups work. An organization whose
 * history is empty has never proved anything, whatever its snapshot count says.
 *
 * Each row links to the run's own page, which is where the node's progress log is - a
 * restore takes minutes, so the page that started it cannot be the page that reports it.
 */
export function RestoreHistory({
  organizationId,
  restores,
}: {
  organizationId: string
  restores: RestoreRunView[]
}) {
  return (
    <Card
      title={t('backup.history.title')}
      description={t('backup.history.description')}
      padded={restores.length === 0}
    >
      {restores.length === 0 ? (
        <EmptyState
          icon={<Icon name="backup" />}
          title={t('backup.history.empty.title')}
          description={t('backup.history.empty.desc')}
        />
      ) : (
        <ul className="divide-y divide-ink-200 dark:divide-ink-800">
          {restores.map((restore) => (
            <li key={restore.id}>
              <Link
                href={`/backups/${organizationId}/restores/${restore.id}`}
                className="flex items-center gap-3 px-4 py-3 md:px-5"
              >
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-ink-900 dark:text-ink-100">
                    {restore.targetLabel}
                  </p>
                  <p className="truncate text-xs text-ink-500 dark:text-ink-400">
                    {restoreModeLabel(restore.mode)} ·{' '}
                    <RelativeTime at={restore.startedAt} fallback={t('backup.restoreRun.notStarted')} />
                    {restore.bytesRestored === null ? null : (
                      <>
                        {' · '}
                        <ByteSize bytes={restore.bytesRestored} />
                      </>
                    )}
                  </p>
                </div>
                <Badge
                  tone={restoreStateTone(restore.state)}
                  dot
                  pulse={!restore.finished}
                  className="shrink-0"
                >
                  {restoreStateLabel(restore.state)}
                </Badge>
                <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}
