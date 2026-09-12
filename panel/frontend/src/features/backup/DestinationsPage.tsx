import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
import {Button, PageHeader, mayWrite, useCurrentOrganization} from '@/shell'
import type {MemberRole} from '@/shell'

import {BackupTabs} from './BackupTabs'
import {DestinationForm} from './DestinationForm'
import {DestinationList} from './DestinationList'
import type {DestinationView} from './backupTypes'

/**
 * `GET /backups/{organizationId}/destinations` - a customer's own places to write to.
 *
 * The platform's shared destinations are listed here too, because somebody choosing where
 * a backup goes has to be able to see what they are choosing. They are not editable from
 * this page and the server refuses it as well.
 */
type DestinationsProps = {
  destinations: DestinationView[]
  /** Where a local destination has to live on the node. */
  nodeBackupRoot: string
  viewerRole: MemberRole
}

export default function DestinationsPage() {
  const {destinations, nodeBackupRoot, viewerRole} = usePage<DestinationsProps>().props
  const organization = useCurrentOrganization()
  const organizationId = organization?.id ?? ''
  const [editing, setEditing] = useState<DestinationView | null>(null)
  const [adding, setAdding] = useState(false)

  const writable = mayWrite(viewerRole)
  const basePath = `/backups/${organizationId}/destinations`

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('backup.destinations.title')} />

      <BackupTabs organizationId={organizationId} />

      <PageHeader
        title={t('backup.destinations.title')}
        description={t('backup.destinations.description')}
        actions={
          writable ? (
            <Button block className="sm:w-auto" onClick={() => setAdding(true)}>
              {t('backup.destinations.add')}
            </Button>
          ) : null
        }
      />

      <DestinationList
        basePath={basePath}
        destinations={destinations}
        writable={writable}
        onEdit={(destination) => setEditing(destination)}
      />

      <DestinationForm
        key={editing?.id ?? 'new'}
        open={adding || editing !== null}
        onClose={() => {
          setAdding(false)
          setEditing(null)
        }}
        basePath={basePath}
        destination={editing}
        nodeBackupRoot={nodeBackupRoot}
      />
    </div>
  )
}
