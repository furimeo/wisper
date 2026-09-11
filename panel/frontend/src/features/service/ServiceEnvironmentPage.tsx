import {Head, usePage} from '@inertiajs/react'

import {Card, PageHeader, mayWrite} from '@/shell'
import type {MemberRole} from '@/shell'

import {EnvVarList} from './EnvVarList'
import {SecretList} from './SecretList'
import {ServiceTabs} from './ServiceTabs'
import type {EnvVar, SecretView, ServiceView} from './serviceTypes'

/**
 * `GET /services/{serviceId}/environment` - the variables and the secrets on one service.
 *
 * One screen for both, because the workload sees one environment and a customer looking
 * for `DATABASE_URL` should not have to remember which list they put it in. Two lists on
 * it, because the difference is real and has to be visible: the first shows values behind
 * a reveal, the second shows only when each was last changed.
 *
 * Nothing on this page can produce a secret's value. `ListSecrets` does not select the
 * column, so it is not in the props, not in a props dump and not in a stack trace - and
 * the panel says that out loud rather than leaving somebody hunting for the button.
 *
 * Variables first: it is the longer list, the one edited most often, and the one somebody
 * scrolls to on a phone. Secrets are rotated rarely and are worth the extra scroll.
 */
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
      <Head title={`${service.name} environment`} />
      <ServiceTabs serviceId={service.id} />

      <PageHeader
        title="Environment"
        description={
          service.site
            ? 'A static site has no running process, so everything here is read while it builds.'
            : 'Handed to the container as its environment. A change reaches the workload the next time it starts.'
        }
      />

      {writable ? null : (
        <Card>
          <p className="text-sm text-ink-700 dark:text-ink-300">
            {service.archived
              ? 'This service is archived, so its environment is read-only. Restore the project from its settings screen.'
              : 'You have read access to this organization, so the controls on this page are off.'}
          </p>
        </Card>
      )}

      <EnvVarList service={service} variables={variables} writable={writable} />

      <SecretList service={service} secrets={secrets} writable={writable} />

      <p className="px-1 text-sm text-ink-500 dark:text-ink-400">
        A name belongs to one list or the other: adding a secret called{' '}
        <code className="font-mono">API_KEY</code> is refused while a plain variable of that
        name exists, and the other way round. <code className="font-mono">WISPER_*</code> is
        reserved for values the platform sets itself.
      </p>
    </div>
  )
}
