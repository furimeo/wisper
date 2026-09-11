import {Head, usePage} from '@inertiajs/react'

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
      <Head title={`${project.name} settings`} />
      <ProjectTabs projectId={project.id} />

      <PageHeader
        title="Settings"
        description={
          writable
            ? 'What this project is called, and what happens to it.'
            : 'You have read access to this organization, so these controls are off.'
        }
      />

      <RenameProjectForm project={project} disabled={!writable} />

      <Card title="About">
        <CardFacts>
          <CardFact label="Address">
            <code className="font-mono">/{project.slug}</code>
          </CardFact>
          <CardFact label="Services">{serviceCount}</CardFact>
          <CardFact label="Opened">
            <RelativeTime at={project.createdAt} />
          </CardFact>
          <CardFact label="Last change">
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
