import {Head, Link} from '@inertiajs/react'

import {useI18n} from '@/i18n'
import {Card, PageHeader} from '@/shell'
import {ServiceTabs} from '@/features/service/ServiceTabs'
import type {ServiceLocation} from '@/features/service/serviceTypes'

export function NoStorageState({service}: {service: ServiceLocation}) {
  const {t} = useI18n()

  return (
    <div className="flex flex-col gap-3">
      <Head title={t('files.page.head_title', {service: service.name})} />
      <ServiceTabs serviceId={service.serviceId} />
      <PageHeader
        title={t('files.page.title')}
        description={t('files.page.no_storage_desc', {service: service.name})}
      />
      <Card>
        <div className="flex flex-col items-center justify-center gap-3 py-12 text-center">
          <div className="rounded-full bg-ink-100 p-3 text-ink-500 dark:bg-ink-800 dark:text-ink-400">
            <svg className="size-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth="1.5"
                d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"
              />
            </svg>
          </div>
          <div className="max-w-md">
            <h3 className="font-medium text-ink-900 dark:text-ink-100">
              {t('files.page.no_storage_title')}
            </h3>
            <p className="mt-1 text-sm text-ink-500 dark:text-ink-400">
              {t('files.page.no_storage_desc', {service: service.name})}
            </p>
          </div>
          <Link
            href={`/services/${service.serviceId}/volumes`}
            className="mt-2 inline-flex items-center gap-1.5 rounded-lg bg-accent-600 px-3.5 py-2 text-sm font-medium text-white shadow-sm hover:bg-accent-700"
          >
            {t('files.page.manage_storage')}
          </Link>
        </div>
      </Card>
    </div>
  )
}
