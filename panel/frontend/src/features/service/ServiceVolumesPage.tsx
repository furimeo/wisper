import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {Button, ButtonLink, Card, EmptyState, Icon, PageHeader, mayWrite} from '@/shell'
import type {MemberRole} from '@/shell'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'

import {ServiceTabs} from './ServiceTabs'
import {VolumeDialog} from './VolumeDialog'
import {VolumeRow} from './VolumeRow'
import type {ServiceView, Volume} from './serviceTypes'

/**
 * `GET /services/{serviceId}/volumes` - the disks attached to one service.
 *
 * A static site reaches this page too, from the same tab strip, and it renders the reason
 * it has no volumes rather than an empty list above an "attach" button that is refused
 * every time. `CreateVolume` throws for a site; a screen that offers the action anyway is
 * a screen teaching customers that the panel's buttons are decorative.
 *
 * The quota bar is at the top rather than beside the button, because the number that
 * decides whether the next volume can exist is worth reading before the form is opened -
 * and on a phone anything beside a button is below it.
 *
 * Attaching a volume pins the service to whichever node holds it. That sentence is on the
 * page, not only in the flash message afterwards: it is the one consequence of this screen
 * that reaches beyond it.
 */
type ServiceVolumesProps = {
  service: ServiceView
  volumes: Volume[]
  /** The VOLUME_BYTES quota for the organization this service is in. */
  allowance: QuotaAllowance
  viewerRole: MemberRole
}

export default function ServiceVolumesPage() {
  const {service, volumes, allowance, viewerRole} = usePage<ServiceVolumesProps>().props
  const [editor, setEditor] = useState<{volume: Volume | null} | null>(null)

  const writable = mayWrite(viewerRole) && !service.archived && service.app
  const attached = volumes.reduce((total, volume) => total + volume.sizeBytes, 0)

  return (
    <div className="flex flex-col gap-4">
      <Head title={`${service.name} volumes`} />
      <ServiceTabs serviceId={service.id} />

      <PageHeader
        title="Volumes"
        description={
          service.site
            ? 'A static site is files on disk already, so there is nothing to mount into it.'
            : 'Directories on the node that outlive the container. Attaching one pins this service to its node.'
        }
        actions={
          writable ? (
            <Button icon={<Icon name="database" />} onClick={() => setEditor({volume: null})}>
              Attach a volume
            </Button>
          ) : null
        }
      />

      {service.site ? (
        <Card title="Nothing to mount">
          <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
            A static site has no running container, so there is nothing for a volume to appear
            inside. The files this site publishes are on the node already - open the file
            manager to browse, edit or upload them, and the deployment history to roll a release
            back.
          </p>
          <div className="mt-3 flex flex-col gap-2 sm:flex-row">
            <ButtonLink variant="secondary" block className="sm:w-auto" href={`/services/${service.id}/files`}>
              Open the file manager
            </ButtonLink>
            <ButtonLink
              variant="secondary"
              block
              className="sm:w-auto"
              href={`/services/${service.id}/deployments`}
            >
              Deployments
            </ButtonLink>
          </div>
        </Card>
      ) : (
        <>
          <Card>
            <QuotaMeter allowance={allowance} className="py-0" />
          </Card>

          {mayWrite(viewerRole) || service.archived ? null : (
            <Card>
              <p className="text-sm text-ink-700 dark:text-ink-300">
                You have read access to this organization, so the controls on this page are off.
              </p>
            </Card>
          )}

          {service.archived ? (
            <Card>
              <p className="text-sm text-ink-700 dark:text-ink-300">
                This service is archived. Its volumes are intact and nothing was deleted - restore
                the project from its settings screen to change them again.
              </p>
            </Card>
          ) : null}

          <Card
            title="Attached"
            description={
              volumes.length === 0
                ? undefined
                : `${volumes.length} ${volumes.length === 1 ? 'volume' : 'volumes'} on this service.`
            }
            padded={false}
          >
            {volumes.length === 0 ? (
              <EmptyState
                icon={<Icon name="database" />}
                title="No volumes attached"
                description="Everything the container writes outside a volume is lost the next time it
                  is recreated - on a redeploy, an image change, a node reboot. Anything you want to
                  keep goes on a volume."
                action={
                  writable ? (
                    <Button onClick={() => setEditor({volume: null})}>Attach the first one</Button>
                  ) : null
                }
              />
            ) : (
              <ul className="divide-y divide-ink-200 dark:divide-ink-800">
                {volumes.map((volume) => (
                  <VolumeRow
                    key={volume.id}
                    volume={volume}
                    onOpen={writable ? () => setEditor({volume}) : undefined}
                  />
                ))}
              </ul>
            )}
          </Card>

          {volumes.length > 0 ? (
            <p className="px-1 text-sm text-ink-500 dark:text-ink-400">
              {volumes.length === 1 ? 'This volume counts' : 'These volumes count'} for{' '}
              {Math.round((attached / Math.max(1, allowance.limit)) * 100)}% of your disk quota.
              Detaching one leaves its data on the node until an operator purges it, so the space
              is freed against the plan but the files are still recoverable.
            </p>
          ) : null}
        </>
      )}

      {editor ? (
        <VolumeDialog
          key={editor.volume?.name ?? '@new'}
          service={service}
          volume={editor.volume}
          onClose={() => setEditor(null)}
        />
      ) : null}
    </div>
  )
}
