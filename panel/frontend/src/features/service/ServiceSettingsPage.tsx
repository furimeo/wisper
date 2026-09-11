import {Head, usePage} from '@inertiajs/react'

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

/**
 * `GET /services/{serviceId}/settings` - everything about a service except whether it is
 * running.
 *
 * The same field groups the new-service form uses, seeded from the service as it stands.
 * That reuse is the point: two forms describing one row is two places for a hint to be
 * wrong, and the one that is wrong is always the one you did not open.
 *
 * Neither the kind nor the address is here, and there is no input for either.
 * `SettingsForm` has no component for them, so a box would be a promise the panel cannot
 * keep - an app and a site are different rows in three CHECK constraints, and a URL does
 * not move because something is always still pointing at the old one.
 *
 * One Save for the whole form rather than one per card. It is one POST and one record on
 * the server; three buttons would imply three independent writes, and the customer who
 * pressed the first two would find out otherwise from a spec the node applied twice.
 */
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
      <Head title={`${service.name} settings`} />
      <ServiceTabs serviceId={service.id} />

      <PageHeader
        title="Settings"
        description={
          service.archived
            ? 'This service is archived. Restore its project before changing anything.'
            : 'Saving publishes a new spec. The node applies it on its next reconcile.'
        }
      />

      <IsolationWarning service={service} />

      {writable ? null : (
        <Card>
          <p className="text-sm text-ink-700 dark:text-ink-300">
            {service.archived
              ? 'Archived services are read-only here. Restore the project from its settings screen.'
              : 'You have read access to this organization, so this form is off.'}
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
        <Card title="Name">
          <div className="flex flex-col gap-4">
            <Input
              {...form.bind('name')}
              label="Name"
              required
              disabled={!writable}
              maxLength={120}
              autoComplete="off"
            />
            <p className="text-sm text-ink-500 dark:text-ink-400">
              Address: <code className="font-mono">/{service.slug}</code>. It cannot be changed -
              something is always still pointing at the old one.
            </p>
          </div>
        </Card>

        {service.app ? (
          <Card title="Container" description="What the node runs, and how it keeps it running.">
            <RuntimeFields
              form={form}
              restartPolicies={restartPolicies}
              disabled={!writable}
              showProbeInterval
            />
          </Card>
        ) : null}

        <Card
          title="Source"
          description={
            service.site
              ? 'A site is built from a repository, and the build produces the directory the node serves.'
              : 'Optional for an app: how your code gets into a language-runtime image.'
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

        <Card title="Limits" description="Enforced by the node through cgroups.">
          <ResourceFields form={form} disabled={!writable} />
        </Card>

        {service.app ? (
          <Card title="Isolation">
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
            Save changes
          </Button>
          <Button
            variant="secondary"
            block
            className="sm:w-auto"
            disabled={!form.dirty || form.processing}
            onClick={() => form.reset()}
          >
            Discard
          </Button>
        </div>
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>

      <DeleteServiceCard service={service} disabled={!mayWrite(viewerRole)} />
    </div>
  )
}
