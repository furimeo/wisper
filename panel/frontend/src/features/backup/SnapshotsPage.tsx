import {Head, usePage} from '@inertiajs/react'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'
import {t} from '@/i18n'
import {Card, PageHeader, mayWrite, useCurrentOrganization} from '@/shell'
import type {MemberRole} from '@/shell'

import {BackupTabs} from './BackupTabs'
import {RestoreHistory} from './RestoreHistory'
import {SnapshotList} from './SnapshotList'
import type {RestorePointView, RestoreRunView} from './backupTypes'
import {useLiveRestores} from './useLiveRestores'

/**
 * `GET /backups/{organizationId}/snapshots` - everything that can be restored from.
 *
 * The banner at the top counts how many of these have never been restored, because that
 * number is the honest measure of the backups. Design §8.3: a backup nobody has restored
 * is not a backup, and verifying costs one press and touches nothing live.
 */
type SnapshotsProps = {
  snapshots: RestorePointView[]
  restores: RestoreRunView[]
  /** BACKUP_BYTES. */
  byteAllowance: QuotaAllowance
  viewerRole: MemberRole
}

export default function SnapshotsPage() {
  const {snapshots, restores, byteAllowance, viewerRole} = usePage<SnapshotsProps>().props
  const organization = useCurrentOrganization()
  const organizationId = organization?.id ?? ''

  const moving =
    snapshots.some((snapshot) => snapshot.state === 'RUNNING' || snapshot.activeRestores > 0) ||
    restores.some((restore) => !restore.finished)
  useLiveRestores(['snapshots', 'restores'], moving)

  const available = snapshots.filter((snapshot) => snapshot.restorable)
  const unproven = available.filter((snapshot) => !snapshot.proven).length

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('backup.snapshots.title')} />

      <BackupTabs organizationId={organizationId} />

      <PageHeader
        title={t('backup.snapshots.title')}
        description={t('backup.snapshots.description')}
      />

      {unproven > 0 ? (
        <p className="rounded-xl border border-degraded/50 bg-degraded/10 px-4 py-3 text-sm leading-relaxed">
          {t('backup.snapshots.unproven', {count: unproven, total: available.length})}
        </p>
      ) : null}

      <SnapshotList
        organizationId={organizationId}
        snapshots={snapshots}
        writable={mayWrite(viewerRole)}
      />

      <RestoreHistory organizationId={organizationId} restores={restores} />

      <Card
        title={t('backup.snapshots.storageUsed')}
        description={t('backup.snapshots.storageUsedDesc')}
      >
        <QuotaMeter allowance={byteAllowance} />
      </Card>
    </div>
  )
}
