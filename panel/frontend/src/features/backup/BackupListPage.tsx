import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'
import {
  Button,
  ButtonLink,
  Card,
  EmptyState,
  Icon,
  PageHeader,
  mayWrite,
  useCurrentOrganization,
} from '@/shell'
import type {MemberRole} from '@/shell'

import {BackupPolicyList} from './BackupPolicyList'
import {BackupScheduleForm} from './BackupScheduleForm'
import {BackupTabs} from './BackupTabs'
import {RestoreHistory} from './RestoreHistory'
import type {
  BackupTargetOption,
  BackupView,
  DestinationView,
  RestoreRunView,
} from './backupTypes'

/**
 * `GET /backups/{organizationId}` - what is copied, and what came of it.
 *
 * Also `GET /backups` for an account that belongs to no organization: the controller
 * renders this same component with every list empty and both allowances and `viewerRole`
 * null. That is a real page with a real explanation, not a redirect - bouncing somebody
 * who pressed "Backups" back where they came from looks like a broken link.
 *
 * The organization comes from the shared prop, which reads `{organizationId}` out of the
 * URL. That is also what the chrome's switcher reads, so the two cannot disagree about
 * which tenant is on screen.
 */
type BackupListProps = {
  policies: BackupView[]
  destinations: DestinationView[]
  targets: BackupTargetOption[]
  restores: RestoreRunView[]
  /** RESTORE_POINT, or null when the account belongs to no organization. */
  snapshotAllowance: QuotaAllowance | null
  /** BACKUP_BYTES, likewise. */
  byteAllowance: QuotaAllowance | null
  viewerRole: MemberRole | null
}

export default function BackupListPage() {
  const {policies, destinations, targets, restores, snapshotAllowance, byteAllowance, viewerRole} =
    usePage<BackupListProps>().props
  const organization = useCurrentOrganization()
  const [editing, setEditing] = useState<BackupView | null>(null)
  const [creating, setCreating] = useState(false)

  if (organization === null || viewerRole === null) {
    return <NoOrganization />
  }

  const writable = mayWrite(viewerRole)
  const usableDestinations = destinations.filter((destination) => destination.enabled)
  const unproven = policies.filter((policy) => policy.snapshotCount === 0).length

  return (
    <div className="flex flex-col gap-4">
      <Head title="Backups" />

      <BackupTabs organizationId={organization.id} />

      <PageHeader
        title="Backups"
        description="The node does the copying and pushes straight to your destination; nothing
          passes through the panel. Restoring is one press, and verifying a snapshot without
          touching anything live is another."
        actions={
          writable && usableDestinations.length > 0 ? (
            <Button block className="sm:w-auto" onClick={() => setCreating(true)}>
              New schedule
            </Button>
          ) : null
        }
      />

      {writable && usableDestinations.length === 0 ? (
        <Card
          title="There is nowhere to put a backup yet"
          description="A schedule needs a destination - an S3-compatible bucket, or a path on the
            node itself. Add one and check it before you rely on it."
          action={
            <ButtonLink href={`/backups/${organization.id}/destinations`}>
              Add a destination
            </ButtonLink>
          }
        />
      ) : null}

      {unproven > 0 ? (
        <p className="rounded-xl border border-degraded/50 bg-degraded/10 px-4 py-3 text-sm leading-relaxed">
          {unproven === 1
            ? 'One schedule has never produced a snapshot.'
            : `${unproven} schedules have never produced a snapshot.`}{' '}
          Run them once by hand rather than finding out on the day you need them.
        </p>
      ) : null}

      <BackupPolicyList
        organizationId={organization.id}
        policies={policies}
        writable={writable}
        onEdit={(policy) => setEditing(policy)}
      />

      <RestoreHistory organizationId={organization.id} restores={restores} />

      {snapshotAllowance || byteAllowance ? (
        <Card title="Your plan" description="What this organization's plan allows for backups.">
          {snapshotAllowance ? <QuotaMeter allowance={snapshotAllowance} /> : null}
          {byteAllowance ? <QuotaMeter allowance={byteAllowance} /> : null}
        </Card>
      ) : null}

      <BackupScheduleForm
        key={editing?.id ?? 'new'}
        open={creating || editing !== null}
        onClose={() => {
          setCreating(false)
          setEditing(null)
        }}
        organizationId={organization.id}
        policy={editing}
        destinations={destinations}
        targets={targets}
      />
    </div>
  )
}

/**
 * `GET /backups` for somebody who belongs to nowhere yet.
 *
 * Not an error and not a redirect. They pressed a real link, and the honest answer is that
 * backups belong to an organization and they are not in one.
 */
function NoOrganization() {
  return (
    <div className="flex flex-col gap-4">
      <Head title="Backups" />
      <PageHeader title="Backups" />
      <Card>
        <EmptyState
          icon={<Icon name="backup" />}
          title="Nothing to back up yet"
          description="Backups belong to an organization, and you are not a member of one. Create
            one, or ask somebody to invite you, and this page fills in."
          action={<ButtonLink href="/orgs">Organizations</ButtonLink>}
        />
      </Card>
    </div>
  )
}
