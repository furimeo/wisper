import {Head, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
import {Card, PageHeader, mayWrite} from '@/shell'
import type {MemberRole} from '@/shell'

import {EnvVarList} from './EnvVarList'
import {SecretList} from './SecretList'
import {ServiceTabs} from './ServiceTabs'
import type {EnvVar, SecretView, ServiceView} from './serviceTypes'

type ServiceEnvironmentProps = {
  service: ServiceView
  variables: EnvVar[]
  secrets: SecretView[]
  viewerRole: MemberRole
}

export default function ServiceEnvironmentPage() {
  const {service, variables, secrets, viewerRole} = usePage<ServiceEnvironmentProps>().props
  const writable = mayWrite(viewerRole) && !service.archived

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('service.environment.head_title', {name: service.name})} />
      <ServiceTabs serviceId={service.id} />

      <PageHeader
        title={t('service.environment.title')}
        description={
          service.site
            ? t('service.environment.description_site')
            : t('service.environment.description_app')
        }
      />

      {writable ? null : (
        <Card>
          <p className="text-sm text-ink-700 dark:text-ink-300">
            {service.archived
              ? t('service.environment.archived_notice')
              : t('service.environment.read_only_notice')}
          </p>
        </Card>
      )}

      <EnvVarList service={service} variables={variables} writable={writable} />

      <SecretList service={service} secrets={secrets} writable={writable} />

      <p className="px-1 text-sm text-ink-500 dark:text-ink-400">
        {t('service.environment.rules_footer')}
      </p>
    </div>
  )
}
