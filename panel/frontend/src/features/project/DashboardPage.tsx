import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {Button, EmptyState, Icon, PageHeader} from '@/shell'

import {CreateProjectDialog} from './CreateProjectDialog'
import {ProjectCard} from './ProjectCard'
import type {ProjectSummary} from './projectTypes'

/**
 * `GET /` - every project the signed-in person can reach.
 *
 * One prop, and it is deliberately not filtered by the organization switcher: the server
 * lists every tenant the account is in, and this page groups by `organizationName` rather
 * than hiding the ones that are not current. A project that disappears because a session
 * and a switcher disagree is a project the customer reports as deleted.
 *
 * The grouping headings only appear when there is more than one tenant. For the common
 * case - one organization, four projects - a heading repeating the name already in the top
 * bar is a row of pixels that says nothing.
 */
type DashboardProps = {
  projects: ProjectSummary[]
}

interface Group {
  organizationId: string
  organizationName: string
  projects: ProjectSummary[]
}

export default function DashboardPage() {
  const {projects} = usePage<DashboardProps>().props
  const [creating, setCreating] = useState(false)
  const groups = groupByOrganization(projects)

  return (
    <div className="flex flex-col gap-4">
      <Head title="Projects" />

      <PageHeader
        title="Projects"
        description="Everything you host, grouped by the organization that owns it."
        actions={
          <Button icon={<Icon name="projects" />} onClick={() => setCreating(true)}>
            New project
          </Button>
        }
      />

      {projects.length === 0 ? (
        <div className="rounded-xl border border-ink-200 bg-white dark:border-ink-800 dark:bg-ink-900">
          <EmptyState
            icon={<Icon name="projects" />}
            title="No projects yet"
            description="A project holds the services that ship together - an app and the static site
              in front of it, say. Open one and you can add the first service straight after."
            action={<Button onClick={() => setCreating(true)}>Open your first project</Button>}
          />
        </div>
      ) : (
        <div className="flex flex-col gap-6">
          {groups.map((group) => (
            <section key={group.organizationId} className="flex flex-col gap-2">
              {groups.length > 1 ? (
                <h2 className="px-1 text-xs font-semibold uppercase tracking-wide text-ink-500 dark:text-ink-400">
                  {group.organizationName}
                </h2>
              ) : null}
              <div className="flex flex-col gap-2">
                {group.projects.map((project) => (
                  <ProjectCard key={project.id} project={project} />
                ))}
              </div>
            </section>
          ))}
        </div>
      )}

      <CreateProjectDialog open={creating} onClose={() => setCreating(false)} />
    </div>
  )
}

/**
 * Tenants in the order the server sent them, projects in the order they arrived inside
 * each.
 *
 * `ListProjects` already sorts: organizations alphabetically, live projects before
 * archived ones within each. Re-sorting here would be a second opinion about an order that
 * is already decided, and the two would drift.
 */
function groupByOrganization(projects: ProjectSummary[]): Group[] {
  const groups: Group[] = []
  const seen = new Map<string, Group>()

  for (const project of projects) {
    let group = seen.get(project.organizationId)
    if (!group) {
      group = {
        organizationId: project.organizationId,
        organizationName: project.organizationName,
        projects: [],
      }
      seen.set(project.organizationId, group)
      groups.push(group)
    }
    group.projects.push(project)
  }
  return groups
}
