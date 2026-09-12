import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'
import {t} from '@/i18n'
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
      <Head title={t('backup.list.title')} />

      <BackupTabs organizationId={organization.id} />

      <PageHeader
        title={t('backup.list.title')}
        description={t('backup.list.description')}
        actions={
          writable && usableDestinations.length > 0 ? (
            <Button block className="sm:w-auto" onClick={() => setCreating(true)}>
              {t('backup.list.action.new')}
            </Button>
          ) : null
        }
      />

      {writable && usableDestinations.length === 0 ? (
        <Card
          title={t('backup.list.noDestTitle')}
          description={t('backup.list.noDestDesc')}
          action={
            <ButtonLink href={`/backups/${organization.id}/destinations`}>
              {t('backup.list.addDest')}
            </ButtonLink>
          }
        />
      ) : null}

      {unproven > 0 ? (
        <p className="rounded-xl border border-degraded/50 bg-degraded/10 px-4 py-3 text-sm leading-relaxed">
          {t('backup.list.unproven', {count: unproven})}
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
        <Card title={t('backup.list.plan.title')} description={t('backup.list.plan.description')}>
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
      <Head title={t('backup.list.title')} />
      <PageHeader title={t('backup.list.title')} />
      <Card>
        <EmptyState
          icon={<Icon name="backup" />}
          title={t('backup.list.noOrg.title')}
          description={t('backup.list.noOrg.desc')}
          action={<ButtonLink href="/orgs">{t('backup.list.noOrg.action')}</ButtonLink>}
        />
      </Card>
    </div>
  )
}
