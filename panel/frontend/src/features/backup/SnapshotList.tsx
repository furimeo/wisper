import {router} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
import {
  Badge,
  ByteSize,
  Card,
  DataList,
  EmptyState,
  Icon,
  RelativeTime,
  askConfirmation,
} from '@/shell'

import type {RestorePointView} from './backupTypes'
import {
  pointStateLabel,
  pointStateTone,
  snapshotSentence,
  targetKindLabel,
  triggerLabel,
} from './backupVocabulary'

/**
 * Every restore point, and the two buttons design §8.3 is about.
 *
 * **Restore** puts the archive back over the live data. It takes a `PRE_RESTORE` snapshot
 * of what is there first, which is the real safety net - but a mis-tap here still costs a
 * customer their afternoon's work while that gets put back, so it asks for the target
 * typed out rather than a single yes. On a phone that is friction, and it is the right
 * friction: everything else on this screen is one press.
 *
 * **Verify** restores somewhere disposable and throws the result away. It overwrites
 * nothing, so it needs no confirmation, and it is the only thing on this page that turns a
 * row from "we have a backup" into "we have restored this backup". A snapshot that has
 * never been verified says so on its row.
 */
export function SnapshotList({
  organizationId,
  snapshots,
  writable,
}: {
  organizationId: string
  snapshots: RestorePointView[]
  writable: boolean
}) {
  const [pending, setPending] = useState<string | null>(null)

  function post(snapshot: RestorePointView, action: string, data: Record<string, string> = {}) {
    setPending(`${snapshot.id}:${action}`)
    router.post(`/backups/${organizationId}/snapshots/${snapshot.id}/${action}`, data, {
      preserveScroll: true,
      onFinish: () => setPending(null),
    })
  }

  async function restore(snapshot: RestorePointView) {
    const confirmed = await askConfirmation({
      title: t('backup.snapshotList.restoreConfirm.title', {target: snapshot.targetLabel}),
      body: t('backup.snapshotList.restoreConfirm.body', {target: snapshot.targetLabel}),
      confirmLabel: t('backup.snapshotList.restoreConfirm.confirm'),
      tone: 'danger',
      requireText: snapshot.targetLabel,
      requireTextLabel: t('backup.snapshotList.restoreConfirm.requireTextLabel', {target: snapshot.targetLabel}),
    })
    if (confirmed) {
      post(snapshot, 'restore', {confirmed: 'true'})
    }
  }

  async function remove(snapshot: RestorePointView) {
    const confirmed = await askConfirmation({
      title: t('backup.snapshotList.deleteConfirm.title'),
      body: t('backup.snapshotList.deleteConfirm.body'),
      confirmLabel: t('backup.snapshotList.deleteConfirm.confirm'),
      tone: 'danger',
    })
    if (confirmed) {
      post(snapshot, 'delete')
    }
  }

  return (
    <Card padded={false}>
      <DataList
        items={snapshots}
        keyOf={(snapshot) => snapshot.id}
        label={t('backup.snapshotList.label')}
        empty={
          <EmptyState
            icon={<Icon name="backup" />}
            title={t('backup.snapshotList.empty.title')}
            description={t('backup.snapshotList.empty.desc')}
          />
        }
        actions={
          writable
            ? (snapshot) =>
                snapshot.restorable
                  ? [
                      {
                        label: pending === `${snapshot.id}:verify` ? t('backup.snapshotList.starting') : t('backup.snapshotList.verify'),
                        onSelect: () => post(snapshot, 'verify'),
                      },
                      {label: t('backup.snapshotList.restoreInPlace'), tone: 'danger', onSelect: () => void restore(snapshot)},
                      {label: t('backup.snapshotList.delete'), tone: 'danger', onSelect: () => void remove(snapshot)},
                    ]
                  : [{label: t('backup.snapshotList.delete'), tone: 'danger', onSelect: () => void remove(snapshot)}]
            : undefined
        }
        primary={(snapshot) => (
          <span className="flex items-center gap-2">
            <span className="truncate">{snapshot.targetLabel}</span>
            <Badge>{targetKindLabel(snapshot.targetKind)}</Badge>
            {snapshot.proven ? <Badge tone="running">{t('backup.snapshotList.verified')}</Badge> : null}
          </span>
        )}
        secondary={(snapshot) => <span className="line-clamp-2">{snapshotSentence(snapshot)}</span>}
        trailing={(snapshot) => (
          <div className="flex flex-col items-end gap-1">
            <Badge
              tone={pointStateTone(snapshot.state)}
              dot
              pulse={snapshot.state === 'RUNNING' || snapshot.activeRestores > 0}
            >
              {pointStateLabel(snapshot.state)}
            </Badge>
            <span className="text-xs text-ink-500 dark:text-ink-400">
              <RelativeTime at={snapshot.startedAt} />
            </span>
          </div>
        )}
        columns={[
          {
            key: 'target',
            header: t('backup.snapshotList.col.target'),
            cell: (snapshot) => (
              <div className="flex flex-col gap-0.5">
                <span>{snapshot.targetLabel}</span>
                <span className="text-xs text-ink-500 dark:text-ink-400">
                  {targetKindLabel(snapshot.targetKind)} · {snapshot.backupName ?? t('backup.snapshotList.noPolicy')}
                </span>
              </div>
            ),
          },
          {
            key: 'taken',
            header: t('backup.snapshotList.col.taken'),
            cell: (snapshot) => (
              <span className="flex flex-col gap-0.5">
                <RelativeTime at={snapshot.startedAt} className="text-xs" />
                <span className="text-xs text-ink-500 dark:text-ink-400">
                  {triggerLabel(snapshot.trigger)}
                </span>
              </span>
            ),
          },
          {
            key: 'size',
            header: t('backup.snapshotList.col.size'),
            align: 'right',
            cell: (snapshot) => <ByteSize bytes={snapshot.sizeBytes} fallback="-" />,
          },
          {key: 'destination', header: t('backup.snapshotList.col.destination'), cell: (snapshot) => snapshot.destinationName},
          {
            key: 'verified',
            header: t('backup.snapshotList.col.verified'),
            cell: (snapshot) =>
              snapshot.proven ? (
                <RelativeTime at={snapshot.lastVerifiedAt} className="text-xs" />
              ) : (
                <span className="text-xs text-degraded">{t('backup.snapshotList.never')}</span>
              ),
          },
          {
            key: 'expires',
            header: t('backup.snapshotList.col.expires'),
            align: 'right',
            cell: (snapshot) => (
              <RelativeTime at={snapshot.expiresAt} fallback="-" className="text-xs" />
            ),
          },
          {
            key: 'state',
            header: t('backup.snapshotList.col.state'),
            align: 'right',
            cell: (snapshot) => (
              <Badge tone={pointStateTone(snapshot.state)} dot>
                {pointStateLabel(snapshot.state)}
              </Badge>
            ),
          },
        ]}
      />
    </Card>
  )
}
