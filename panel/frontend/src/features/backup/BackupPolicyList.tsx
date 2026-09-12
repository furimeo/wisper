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

import type {BackupView} from './backupTypes'
import {policySentence, policyTone, scheduleSentence, targetKindLabel} from './backupVocabulary'

/**
 * The policies, with the three things you do to one behind a swipe.
 *
 * "Run now" is on every policy including the scheduled ones, and it is the button that
 * turns a configuration into a backup. A policy that has never produced a snapshot is
 * drawn as a warning rather than as green for the same reason: enabled is not the same as
 * working, and finding out which on the night you need it is the failure design §8.3 is
 * about.
 */
export function BackupPolicyList({
  organizationId,
  policies,
  writable,
  onEdit,
}: {
  organizationId: string
  policies: BackupView[]
  writable: boolean
  onEdit: (policy: BackupView) => void
}) {
  const [running, setRunning] = useState<string | null>(null)

  function run(policy: BackupView) {
    setRunning(policy.id)
    router.post(
      `/backups/${organizationId}/policies/${policy.id}/run`,
      {},
      {preserveScroll: true, onFinish: () => setRunning(null)},
    )
  }

  async function remove(policy: BackupView) {
    const confirmed = await askConfirmation({
      title: t('backup.policy.deleteConfirm.title', {name: policy.name}),
      body: t('backup.policy.deleteConfirm.body'),
      confirmLabel: t('backup.policy.deleteConfirm.confirm'),
      tone: 'danger',
      requireText: policy.name,
      requireTextLabel: t('backup.policy.deleteConfirm.requireTextLabel', {name: policy.name}),
    })
    if (confirmed) {
      router.post(
        `/backups/${organizationId}/policies/${policy.id}/delete`,
        {confirmation: policy.name},
        {preserveScroll: true},
      )
    }
  }

  return (
    <Card padded={false}>
      <DataList
        items={policies}
        keyOf={(policy) => policy.id}
        label={t('backup.policy.label')}
        empty={
          <EmptyState
            icon={<Icon name="backup" />}
            title={t('backup.policy.empty.title')}
            description={t('backup.policy.empty.description')}
          />
        }
        actions={
          writable
            ? (policy) => [
                {
                  label: running === policy.id ? t('backup.policy.starting') : t('backup.policy.runNow'),
                  onSelect: () => run(policy),
                },
                {label: t('backup.policy.edit'), onSelect: () => onEdit(policy)},
                {label: t('backup.policy.delete'), tone: 'danger', onSelect: () => void remove(policy)},
              ]
            : undefined
        }
        primary={(policy) => (
          <span className="flex items-center gap-2">
            <span className="truncate">{policy.name}</span>
            <Badge>{targetKindLabel(policy.targetKind)}</Badge>
          </span>
        )}
        secondary={(policy) => <span className="line-clamp-2">{policySentence(policy)}</span>}
        trailing={(policy) => (
          <div className="flex flex-col items-end gap-1">
            <Badge tone={policyTone(policy)} dot pulse={policy.lastStatus === 'RUNNING'}>
              {policy.snapshotCount === 0 ? t('backup.policy.neverRun') : t('backup.policy.kept', {count: policy.snapshotCount})}
            </Badge>
            <span className="text-xs text-ink-500 dark:text-ink-400">
              <RelativeTime at={policy.latestSnapshotAt} fallback={t('backup.policy.noSnapshot')} />
            </span>
          </div>
        )}
        columns={[
          {
            key: 'name',
            header: t('backup.policy.col.schedule'),
            cell: (policy) => (
              <div className="flex flex-col gap-0.5">
                <span>{policy.name}</span>
                <span className="text-xs text-ink-500 dark:text-ink-400">
                  {targetKindLabel(policy.targetKind)} · {policy.targetLabel}
                </span>
              </div>
            ),
          },
          {
            key: 'when',
            header: t('backup.policy.col.when'),
            cell: (policy) => (
              <span className="text-xs">
                {scheduleSentence(policy.schedule, policy.timezone)}
                {policy.enabled ? null : (
                  <span className="ml-1 text-ink-500 dark:text-ink-400">{t('backup.policy.off')}</span>
                )}
              </span>
            ),
          },
          {key: 'destination', header: t('backup.policy.col.destination'), cell: (policy) => policy.destinationName},
          {
            key: 'kept',
            header: t('backup.policy.col.kept'),
            align: 'right',
            cell: (policy) => (
              <span className="tabular-nums">
                {policy.snapshotCount}
                <span className="text-ink-500 dark:text-ink-400">
                  {' '}
                  / {policy.retentionCount}
                </span>
              </span>
            ),
          },
          {
            key: 'stored',
            header: t('backup.policy.col.stored'),
            align: 'right',
            cell: (policy) => <ByteSize bytes={policy.storedBytes} />,
          },
          {
            key: 'last',
            header: t('backup.policy.col.lastRun'),
            align: 'right',
            cell: (policy) => (
              <span className="flex flex-col items-end gap-0.5">
                <RelativeTime at={policy.lastRunAt} fallback={t('backup.snapshotList.never')} className="text-xs" />
                <Badge tone={policyTone(policy)}>
                  {policy.lastStatus === null ? t('backup.policy.neverRun').toLowerCase() : policy.lastStatus.toLowerCase()}
                </Badge>
              </span>
            ),
          },
          {
            key: 'next',
            header: t('backup.policy.col.next'),
            align: 'right',
            cell: (policy) => (
              <RelativeTime at={policy.nextRunAt} fallback="-" className="text-xs" />
            ),
          },
        ]}
      />
    </Card>
  )
}
