import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
import {Button, ButtonLink, Card, EmptyState, Icon, PageHeader, mayWrite} from '@/shell'
import type {MemberRole} from '@/shell'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'

import {ServiceTabs} from './ServiceTabs'
import {VolumeDialog} from './VolumeDialog'
import {VolumeRow} from './VolumeRow'
import type {ServiceView, Volume} from './serviceTypes'

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
      <Head title={t('service.volumes.head_title', {name: service.name})} />
      <ServiceTabs serviceId={service.id} />

      <PageHeader
        title={t('service.volumes.title')}
        description={
          service.site
            ? t('service.volumes.description_site')
            : t('service.volumes.description_app')
        }
        actions={
          writable ? (
            <Button icon={<Icon name="database" />} onClick={() => setEditor({volume: null})}>
              {t('service.volumes.attach_button')}
            </Button>
          ) : null
        }
      />

      {service.site ? (
        <Card title={t('service.volumes.nothing_to_mount_title')}>
          <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
            {t('service.volumes.nothing_to_mount_body')}
          </p>
          <div className="mt-3 flex flex-col gap-2 sm:flex-row">
            <ButtonLink variant="secondary" block className="sm:w-auto" href={`/services/${service.id}/files`}>
              {t('service.volumes.open_file_manager')}
            </ButtonLink>
            <ButtonLink
              variant="secondary"
              block
              className="sm:w-auto"
              href={`/services/${service.id}/deployments`}
            >
              {t('service.volumes.deployments_button')}
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
                {t('service.volumes.read_only_notice')}
              </p>
            </Card>
          )}

          {service.archived ? (
            <Card>
              <p className="text-sm text-ink-700 dark:text-ink-300">
                {t('service.volumes.archived_notice')}
              </p>
            </Card>
          ) : null}

          <Card
            title={t('service.volumes.attached_card_title')}
            description={
              volumes.length === 0
                ? undefined
                : t('service.volumes.attached_card_desc', {count: volumes.length})
            }
            padded={false}
          >
            {volumes.length === 0 ? (
              <EmptyState
                icon={<Icon name="database" />}
                title={t('service.volumes.empty_title')}
                description={t('service.volumes.empty_desc')}
                action={
                  writable ? (
                    <Button onClick={() => setEditor({volume: null})}>{t('service.volumes.attach_first')}</Button>
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
              {volumes.length === 1
                ? t('service.volumes.quota_foot_single', {
                    percent: Math.round((attached / Math.max(1, allowance.limit)) * 100),
                  })
                : t('service.volumes.quota_foot_multi', {
                    percent: Math.round((attached / Math.max(1, allowance.limit)) * 100),
                  })}
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
