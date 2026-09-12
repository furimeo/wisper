import {Head, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
import {Button, Card, Input, PageHeader, mayWrite, useFormFields} from '@/shell'
import type {MemberRole} from '@/shell'

import {BuildFields} from './BuildFields'
import {DeleteServiceCard} from './DeleteServiceCard'
import {IsolationFields} from './IsolationFields'
import {IsolationWarning} from './IsolationWarning'
import {RepositoryFields} from './RepositoryFields'
import {ResourceFields} from './ResourceFields'
import {RuntimeFields} from './RuntimeFields'
import {ServiceTabs} from './ServiceTabs'
import {valuesFromService} from './serviceFormValues'
import type {BuildPreset, RestartPolicy, RuntimeIsolation, ServiceView} from './serviceTypes'

type ServiceSettingsProps = {
  service: ServiceView
  viewerRole: MemberRole
  presets: BuildPreset[]
  isolations: RuntimeIsolation[]
  restartPolicies: RestartPolicy[]
}

export default function ServiceSettingsPage() {
  const {service, viewerRole, presets, isolations, restartPolicies} =
    usePage<ServiceSettingsProps>().props

  const form = useFormFields(valuesFromService(service))
  const writable = mayWrite(viewerRole) && !service.archived

  function save() {
    form.submit(`/services/${service.id}/settings`)
  }

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('service.settings.head_title', {name: service.name})} />
      <ServiceTabs serviceId={service.id} />

      <PageHeader
        title={t('service.settings.title')}
        description={
          service.archived
            ? t('service.settings.description_archived')
            : t('service.settings.description_active')
        }
      />

      <IsolationWarning service={service} />

      {writable ? null : (
        <Card>
          <p className="text-sm text-ink-700 dark:text-ink-300">
            {service.archived
              ? t('service.settings.archived_notice')
              : t('service.settings.read_only_notice')}
          </p>
        </Card>
      )}

      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          save()
        }}
      >
        <Card title={t('service.settings.name_card_title')}>
          <div className="flex flex-col gap-4">
            <Input
              {...form.bind('name')}
              label={t('service.settings.name_label')}
              required
              disabled={!writable}
              maxLength={120}
              autoComplete="off"
            />
            <p className="text-sm text-ink-500 dark:text-ink-400">
              {t('service.settings.address_hint', {slug: service.slug})}
            </p>
          </div>
        </Card>

        {service.app ? (
          <Card title={t('service.settings.container_title')} description={t('service.settings.container_description')}>
            <RuntimeFields
              form={form}
              restartPolicies={restartPolicies}
              disabled={!writable}
              showProbeInterval
            />
          </Card>
        ) : null}

        <Card
          title={t('service.settings.source_title')}
          description={
            service.site
              ? t('service.settings.source_description_site')
              : t('service.settings.source_description_app')
          }
        >
          <div className="flex flex-col gap-4">
            <RepositoryFields
              form={form}
              hasCredential={service.hasRepositoryCredential}
              disabled={!writable}
            />
            <BuildFields
              form={form}
              presets={presets}
              required={service.site}
              disabled={!writable}
            />
          </div>
        </Card>

        <Card title={t('service.settings.limits_title')} description={t('service.settings.limits_description')}>
          <ResourceFields form={form} disabled={!writable} />
        </Card>

        {service.app ? (
          <Card title={t('service.settings.isolation_title')}>
            <IsolationFields form={form} isolations={isolations} disabled={!writable} />
          </Card>
        ) : null}

        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button
            block
            className="sm:w-auto"
            loading={form.processing}
            disabled={!writable || !form.dirty}
            onClick={save}
          >
            {t('service.settings.save_changes')}
          </Button>
          <Button
            variant="secondary"
            block
            className="sm:w-auto"
            disabled={!form.dirty || form.processing}
            onClick={() => form.reset()}
          >
            {t('service.settings.discard')}
          </Button>
        </div>
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>

      <DeleteServiceCard service={service} disabled={!mayWrite(viewerRole)} />
    </div>
  )
}
