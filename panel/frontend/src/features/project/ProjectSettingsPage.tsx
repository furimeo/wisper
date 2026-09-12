import {Head, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
import {Card, CardFact, CardFacts, PageHeader, RelativeTime, mayWrite} from '@/shell'
import type {MemberRole} from '@/shell'

import {mayAdminister} from '@/features/org/roleVocabulary'

import {ProjectLifecycleCard} from './ProjectLifecycleCard'
import {ProjectTabs} from './ProjectTabs'
import {RenameProjectForm} from './RenameProjectForm'
import type {Project} from './projectTypes'

/**
 * `GET /projects/{projectId}/settings` - the project itself, rather than what is in it.
 *
 * Three cards in order of how much they can hurt: what it is called, what it is, and the
 * pair of buttons that stop or remove it. A viewer sees all three and can operate none of
 * them, which is on purpose - a screen that hides its controls entirely leaves somebody
 * asking whether the panel can do the thing at all.
 */
type ProjectSettingsProps = {
  project: Project
  serviceCount: number
  viewerRole: MemberRole
}

export default function ProjectSettingsPage() {
  const {project, serviceCount, viewerRole} = usePage<ProjectSettingsProps>().props
  const writable = mayWrite(viewerRole)

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('project.settings.pageTitle', {name: project.name})} />
      <ProjectTabs projectId={project.id} />

      <PageHeader
        title={t('project.settings.title')}
        description={
          writable
            ? t('project.settings.descWritable')
            : t('project.settings.descReadOnly')
        }
      />

      <RenameProjectForm project={project} disabled={!writable} />

      <Card title={t('project.settings.aboutTitle')}>
        <CardFacts>
          <CardFact label={t('project.settings.address')}>
            <code className="font-mono">/{project.slug}</code>
          </CardFact>
          <CardFact label={t('project.settings.services')}>{serviceCount}</CardFact>
          <CardFact label={t('project.settings.opened')}>
            <RelativeTime at={project.createdAt} />
          </CardFact>
          <CardFact label={t('project.settings.lastChange')}>
            <RelativeTime at={project.updatedAt} />
          </CardFact>
        </CardFacts>
      </Card>

      <ProjectLifecycleCard
        project={project}
        serviceCount={serviceCount}
        mayArchive={writable}
        mayDelete={mayAdminister(viewerRole)}
      />
    </div>
  )
}
