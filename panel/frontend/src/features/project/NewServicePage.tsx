import {Head, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
import {Button, ButtonLink, Card, Checkbox, Input, PageHeader, mayWrite, useFormFields} from '@/shell'
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
      <Head title={t('project.newService.title')} />

      <PageHeader
        title={t('project.newService.title')}
        description={t('project.newService.description', {name: project.name})}
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
              ? t('project.newService.archivedNotice')
              : t('project.newService.readOnlyNotice')}
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
        <Card title={t('project.newService.whatItIsTitle')}>
          <div className="flex flex-col gap-4">
            <ServiceKindChooser
              kinds={kinds}
              value={kind}
              disabled={!writable}
              onChange={(chosen) => form.set('kind', chosen)}
            />

            <Input
              {...form.bind('name')}
              label={t('project.newService.name')}
              required
              disabled={!writable}
              maxLength={120}
              autoComplete="off"
              placeholder={kind === 'SITE' ? 'Marketing site' : 'API'}
            />

            <Input
              {...form.bind('slug')}
              label={t('project.newService.slug')}
              disabled={!writable}
              maxLength={63}
              autoComplete="off"
              inputMode="url"
              placeholder={derived || 'api'}
              hint={
                derived
                  ? t('project.newService.slugDerivedHint', {slug: derived})
                  : slugRule(2)
              }
            />
          </div>
        </Card>

        {kind === 'APP' ? (
          <Card title={t('project.newService.containerTitle')} description={t('project.newService.containerDesc')}>
            <RuntimeFields
              form={form}
              restartPolicies={restartPolicies}
              disabled={!writable}
            />
          </Card>
        ) : null}

        {kind === 'APP' ? (
          <Card
            title={t('project.newService.storageTitle')}
            description={t('project.newService.storageDesc')}
          >
            <div className="flex flex-col gap-4">
              <Checkbox
                {...form.check('createVolume')}
                label={t('project.newService.createVolumeLabel')}
                disabled={!writable}
              />
              {form.data.createVolume ? (
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                  <Input
                    {...form.bind('volumeMountPath')}
                    label={t('project.newService.volumeMountPath')}
                    required
                    disabled={!writable}
                    placeholder="/app"
                    hint={t('project.newService.volumeMountPathHint')}
                  />
                  <Input
                    {...form.bind('volumeSizeMib')}
                    label={t('project.newService.volumeSizeMib')}
                    type="number"
                    inputMode="numeric"
                    min={1}
                    max={4194304}
                    required
                    disabled={!writable}
                    hint={t('project.newService.volumeSizeHint')}
                  />
                </div>
              ) : null}
            </div>
          </Card>
        ) : null}

        {kind === '' ? null : (
          <Card
            title={t('project.newService.sourceTitle')}
            description={
              kind === 'SITE'
                ? t('project.newService.sourceDescSite')
                : t('project.newService.sourceDescApp')
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
          <Card title={t('project.newService.limitsTitle')} description={t('project.newService.limitsDesc')}>
            <ResourceFields
              form={form}
              memoryAllowance={memoryAllowance}
              cpuAllowance={cpuAllowance}
              disabled={!writable}
            />
          </Card>
        )}

        {kind === 'APP' ? (
          <Card title={t('project.newService.isolationTitle')}>
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
            {t('project.newService.submit')}
          </Button>
          <ButtonLink
            variant="secondary"
            block
            className="sm:w-auto"
            href={`/projects/${project.id}`}
          >
            {t('project.newService.cancel')}
          </ButtonLink>
        </div>
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </div>
  )
}
