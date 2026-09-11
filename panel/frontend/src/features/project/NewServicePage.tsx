import {Head, usePage} from '@inertiajs/react'

import {Button, ButtonLink, Card, Input, PageHeader, mayWrite, useFormFields} from '@/shell'
import type {MemberRole} from '@/shell'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'
import {BuildFields} from '@/features/service/BuildFields'
import {IsolationFields} from '@/features/service/IsolationFields'
import {RepositoryFields} from '@/features/service/RepositoryFields'
import {ResourceFields} from '@/features/service/ResourceFields'
import {RuntimeFields} from '@/features/service/RuntimeFields'
import {valuesFromDraft} from '@/features/service/serviceFormValues'
import type {
  BuildPreset,
  RestartPolicy,
  RuntimeIsolation,
  ServiceDraft,
  ServiceKind,
} from '@/features/service/serviceTypes'

import {ServiceKindChooser} from './ServiceKindChooser'
import {deriveSlug, slugRule} from './deriveSlug'
import type {Project} from './projectTypes'

/**
 * `GET /projects/{projectId}/services/new` - adding a service to a project.
 *
 * The form branches on kind and it branches hard: a static site has no image, no port, no
 * argv and no health check, and `ServiceShape` refuses all four. Rendering them greyed out
 * would be a form that lies about what it accepts, so before a kind is chosen there is
 * nothing below the chooser but the name.
 *
 * Every vocabulary - kinds, presets, isolations, restart policies - comes from the server
 * rather than from a constant here, so the page cannot offer a value a CHECK constraint
 * will refuse. `defaults` is the same `ServiceDraft` the use-case would have applied, which
 * is what makes the numbers in the boxes and the numbers an untouched submission produces
 * the same numbers.
 */
type NewServiceProps = {
  project: Project
  viewerRole: MemberRole
  kinds: ServiceKind[]
  presets: BuildPreset[]
  isolations: RuntimeIsolation[]
  restartPolicies: RestartPolicy[]
  defaults: ServiceDraft
  /** SERVICE, MEMORY_BYTES, CPU_MILLICORES, in that order. */
  allowances: QuotaAllowance[]
}

export default function NewServicePage() {
  const {project, viewerRole, kinds, presets, isolations, restartPolicies, defaults, allowances} =
    usePage<NewServiceProps>().props

  const form = useFormFields(valuesFromDraft(defaults))
  const writable = mayWrite(viewerRole) && !project.archived
  const kind = form.data.kind as ServiceKind | ''
  const derived = deriveSlug(form.data.name)

  const serviceAllowance = allowances.find((allowance) => allowance.resource === 'SERVICE')
  const memoryAllowance = allowances.find((allowance) => allowance.resource === 'MEMORY_BYTES')
  const cpuAllowance = allowances.find((allowance) => allowance.resource === 'CPU_MILLICORES')
  const full = serviceAllowance ? serviceAllowance.used >= serviceAllowance.limit : false

  function submit() {
    form.submit(`/projects/${project.id}/services`)
  }

  return (
    <div className="flex flex-col gap-4">
      <Head title="New service" />

      <PageHeader
        title="New service"
        description={`Adding to ${project.name}. The kind cannot be changed afterwards; everything
          else can.`}
      />

      {serviceAllowance && full ? (
        <Card>
          <QuotaMeter allowance={serviceAllowance} className="py-0" />
        </Card>
      ) : null}

      {writable ? null : (
        <Card>
          <p className="text-sm text-ink-700 dark:text-ink-300">
            {project.archived
              ? 'This project is archived. Restore it from Settings before adding a service.'
              : 'You have read access to this organization, so this form is off.'}
          </p>
        </Card>
      )}

      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <Card title="What it is">
          <div className="flex flex-col gap-4">
            <ServiceKindChooser
              kinds={kinds}
              value={kind}
              disabled={!writable}
              onChange={(chosen) => form.set('kind', chosen)}
            />

            <Input
              {...form.bind('name')}
              label="Name"
              required
              disabled={!writable}
              maxLength={120}
              autoComplete="off"
              placeholder={kind === 'SITE' ? 'Marketing site' : 'API'}
            />

            <Input
              {...form.bind('slug')}
              label="Address"
              disabled={!writable}
              maxLength={63}
              autoComplete="off"
              inputMode="url"
              placeholder={derived || 'api'}
              hint={
                derived
                  ? `Leave it empty and the address becomes "${derived}". It cannot be changed later.`
                  : slugRule(2)
              }
            />
          </div>
        </Card>

        {kind === 'APP' ? (
          <Card title="Container" description="What the node runs, and how it keeps it running.">
            <RuntimeFields
              form={form}
              restartPolicies={restartPolicies}
              disabled={!writable}
            />
          </Card>
        ) : null}

        {kind === '' ? null : (
          <Card
            title="Source"
            description={
              kind === 'SITE'
                ? 'A site is built from a repository, and the build produces the directory the node serves.'
                : 'Optional for an app: how your code gets into a language-runtime image.'
            }
          >
            <div className="flex flex-col gap-4">
              <RepositoryFields form={form} disabled={!writable} />
              <BuildFields
                form={form}
                presets={presets}
                required={kind === 'SITE'}
                disabled={!writable}
              />
            </div>
          </Card>
        )}

        {kind === '' ? null : (
          <Card title="Limits" description="Enforced by the node through cgroups.">
            <ResourceFields
              form={form}
              memoryAllowance={memoryAllowance}
              cpuAllowance={cpuAllowance}
              disabled={!writable}
            />
          </Card>
        )}

        {kind === 'APP' ? (
          <Card title="Isolation">
            <IsolationFields form={form} isolations={isolations} disabled={!writable} />
          </Card>
        ) : null}

        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button
            block
            className="sm:w-auto"
            loading={form.processing}
            disabled={!writable || full || kind === ''}
            onClick={submit}
          >
            Create service
          </Button>
          <ButtonLink
            variant="secondary"
            block
            className="sm:w-auto"
            href={`/projects/${project.id}`}
          >
            Cancel
          </ButtonLink>
        </div>
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </div>
  )
}
