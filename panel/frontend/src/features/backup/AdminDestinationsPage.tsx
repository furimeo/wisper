import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {Button, PageHeader} from '@/shell'

import {DestinationForm} from './DestinationForm'
import {DestinationList} from './DestinationList'
import type {DestinationView} from './backupTypes'

/**
 * `GET /admin/backups` - the destinations every tenant may push to.
 *
 * These are the rows with no organization. They exist so a small platform can run one
 * offsite bucket for everybody rather than asking each customer to bring an S3 account,
 * and they show up read-only on every tenant's own destinations page.
 *
 * No membership is involved: `/admin/**` requires `ROLE_ADMIN`, and a platform-wide
 * destination belongs to no tenant. Every write from here passes a null organization to
 * the use-case, which is what confines this screen to exactly these rows - the same method
 * called with an organization id cannot touch one of them, and this one cannot touch a
 * customer's.
 */
type AdminDestinationsProps = {
  destinations: DestinationView[]
  nodeBackupRoot: string
}

const BASE_PATH = '/admin/backups/destinations'

export default function AdminDestinationsPage() {
  const {destinations, nodeBackupRoot} = usePage<AdminDestinationsProps>().props
  const [editing, setEditing] = useState<DestinationView | null>(null)
  const [adding, setAdding] = useState(false)

  const unchecked = destinations.filter(
    (destination) => destination.enabled && !destination.provenReachable,
  ).length

  return (
    <div className="flex flex-col gap-4">
      <Head title="Backup destinations" />

      <PageHeader
        title="Backup destinations"
        description="Shared by every organization on this platform. A tenant sees them on their
          own destinations page and can point a schedule at one, but cannot change or delete it."
        actions={
          <Button block className="sm:w-auto" onClick={() => setAdding(true)}>
            Add a destination
          </Button>
        }
      />

      {unchecked > 0 ? (
        <p className="rounded-xl border border-degraded/50 bg-degraded/10 px-4 py-3 text-sm leading-relaxed">
          {unchecked === 1
            ? 'One enabled destination has never been checked.'
            : `${unchecked} enabled destinations have never been checked.`}{' '}
          Every tenant on the platform can point a schedule at these, so a bucket with the wrong
          credentials fails for all of them at once - and quietly.
        </p>
      ) : null}

      <DestinationList
        basePath={BASE_PATH}
        destinations={destinations}
        writable
        onEdit={(destination) => setEditing(destination)}
      />

      <DestinationForm
        key={editing?.id ?? 'new'}
        open={adding || editing !== null}
        onClose={() => {
          setAdding(false)
          setEditing(null)
        }}
        basePath={BASE_PATH}
        destination={editing}
        nodeBackupRoot={nodeBackupRoot}
      />
    </div>
  )
}
