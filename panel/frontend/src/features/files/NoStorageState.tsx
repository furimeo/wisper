import {Head, Link, router} from '@inertiajs/react'
import {useState} from 'react'

import {useI18n} from '@/i18n'
import {Button, Card, PageHeader} from '@/shell'
import {ServiceTabs} from '@/features/service/ServiceTabs'
import type {ServiceLocation} from '@/features/service/serviceTypes'

export function NoStorageState({service}: {service: ServiceLocation}) {
  const {t} = useI18n()
  const [initializing, setInitializing] = useState(false)

  function quickInit() {
    setInitializing(true)
    router.post(
      `/services/${service.serviceId}/volumes`,
      {
        name: 'data',
        mountPath: '/app',
        sizeMebibytes: 5120,
        readOnly: false,
        backupEnabled: true,
        returnTo: `/services/${service.serviceId}/files`,
      },
      {
        onFinish: () => setInitializing(false),
      },
    )
  }

  return (
    <div className="flex flex-col gap-3">
      <Head title={t('files.page.head_title', {service: service.name})} />
      <ServiceTabs serviceId={service.serviceId} />
      <PageHeader
        title={t('files.page.title')}
        description={t('files.page.no_storage_desc', {service: service.name})}
      />
      <Card>
        <div className="flex flex-col items-center justify-center gap-4 py-12 text-center">
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
            <h3 className="text-base font-semibold text-ink-900 dark:text-ink-100">
              {t('files.page.no_storage_title')}
            </h3>
            <p className="mt-1 text-sm text-ink-500 dark:text-ink-400">
              {t('files.page.no_storage_desc', {service: service.name})}
            </p>
          </div>
          <div className="flex flex-wrap items-center justify-center gap-3">
            <Button loading={initializing} onClick={quickInit}>
              {t('files.page.quick_init_storage')}
            </Button>
            <Link
              href={`/services/${service.serviceId}/volumes`}
              className="inline-flex items-center gap-1.5 rounded-lg border border-ink-300 bg-white px-3.5 py-2 text-sm font-medium text-ink-700 shadow-sm hover:bg-ink-50 dark:border-ink-700 dark:bg-ink-800 dark:text-ink-200 dark:hover:bg-ink-700"
            >
              {t('files.page.manage_storage')}
            </Link>
          </div>
        </div>
      </Card>
    </div>
  )
}
