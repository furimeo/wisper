import {router} from '@inertiajs/react'
import {useState} from 'react'

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
      title: `Restore ${snapshot.targetLabel} from this snapshot?`,
      body:
        `Everything in ${snapshot.targetLabel} is replaced by what was there when this snapshot ` +
        'was taken. Anything written since is gone from the live copy - a snapshot of the current ' +
        'data is taken first, so it can be put back, but that is another restore and more minutes.',
      confirmLabel: 'Restore it',
      tone: 'danger',
      requireText: snapshot.targetLabel,
      requireTextLabel: `Type ${snapshot.targetLabel} to confirm`,
    })
    if (confirmed) {
      post(snapshot, 'restore', {confirmed: 'true'})
    }
  }

  async function remove(snapshot: RestorePointView) {
    const confirmed = await askConfirmation({
      title: 'Delete this snapshot?',
      body:
        'It comes off the list now and the archive itself goes when the node next prunes. If it ' +
        'is the only copy of something, there will be no way back to it.',
      confirmLabel: 'Delete it',
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
        label="snapshots"
        empty={
          <EmptyState
            icon={<Icon name="backup" />}
            title="No snapshots yet"
            description="A schedule produces these, and so does pressing Run now on one. Until
              there is at least one, there is nothing to restore from."
          />
        }
        actions={
          writable
            ? (snapshot) =>
                snapshot.restorable
                  ? [
                      {
                        label: pending === `${snapshot.id}:verify` ? 'Starting…' : 'Verify it works',
                        onSelect: () => post(snapshot, 'verify'),
                      },
                      {label: 'Restore in place', tone: 'danger', onSelect: () => void restore(snapshot)},
                      {label: 'Delete', tone: 'danger', onSelect: () => void remove(snapshot)},
                    ]
                  : [{label: 'Delete', tone: 'danger', onSelect: () => void remove(snapshot)}]
            : undefined
        }
        primary={(snapshot) => (
          <span className="flex items-center gap-2">
            <span className="truncate">{snapshot.targetLabel}</span>
            <Badge>{targetKindLabel(snapshot.targetKind)}</Badge>
            {snapshot.proven ? <Badge tone="running">verified</Badge> : null}
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
            header: 'Snapshot of',
            cell: (snapshot) => (
              <div className="flex flex-col gap-0.5">
                <span>{snapshot.targetLabel}</span>
                <span className="text-xs text-ink-500 dark:text-ink-400">
                  {targetKindLabel(snapshot.targetKind)} · {snapshot.backupName ?? 'no policy'}
                </span>
              </div>
            ),
          },
          {
            key: 'taken',
            header: 'Taken',
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
            header: 'Size',
            align: 'right',
            cell: (snapshot) => <ByteSize bytes={snapshot.sizeBytes} fallback="unknown" />,
          },
          {key: 'destination', header: 'Destination', cell: (snapshot) => snapshot.destinationName},
          {
            key: 'verified',
            header: 'Verified',
            cell: (snapshot) =>
              snapshot.proven ? (
                <RelativeTime at={snapshot.lastVerifiedAt} className="text-xs" />
              ) : (
                <span className="text-xs text-degraded">never</span>
              ),
          },
          {
            key: 'expires',
            header: 'Expires',
            align: 'right',
            cell: (snapshot) => (
              <RelativeTime at={snapshot.expiresAt} fallback="—" className="text-xs" />
            ),
          },
          {
            key: 'state',
            header: 'State',
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
