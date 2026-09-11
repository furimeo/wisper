import {Head, usePage} from '@inertiajs/react'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'
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
      <Head title="Snapshots" />

      <BackupTabs organizationId={organizationId} />

      <PageHeader
        title="Snapshots"
        description="Each one is a point you can go back to. Restoring puts it over the live data
          after taking a snapshot of what is there now; verifying restores it somewhere disposable
          and throws the copy away."
      />

      {unproven > 0 ? (
        <p className="rounded-xl border border-degraded/50 bg-degraded/10 px-4 py-3 text-sm leading-relaxed">
          {unproven === 1
            ? 'One snapshot has never been restored.'
            : `${unproven} of your ${available.length} restorable snapshots have never been restored.`}{' '}
          Verifying one takes a few minutes and touches nothing live - it is the difference
          between having backups and knowing they work.
        </p>
      ) : null}

      <SnapshotList
        organizationId={organizationId}
        snapshots={snapshots}
        writable={mayWrite(viewerRole)}
      />

      <RestoreHistory organizationId={organizationId} restores={restores} />

      <Card
        title="Storage used"
        description="Across every destination this organization writes to."
      >
        <QuotaMeter allowance={byteAllowance} />
      </Card>
    </div>
  )
}
