import {usePage} from '@inertiajs/react'

import {sectionFor} from './NavigationDestinations'

/**
 * Where the customer is, derived from what the controller sent.
 *
 * There is no client-side routing table in this panel and there is not going to be, so a
 * breadcrumb cannot be looked up in one. It is assembled instead from the two things the
 * page already carries: the URL, which says which section this is, and the props, whose
 * names are fixed by `docs/contracts/pages.md`. Every prop read below is in that
 * contract - `project` on the project screens, `service` on all nine of a service's
 * sub-pages, `deployment`, `database`, `node`, `tenant`, `plan`, `engine`, `restore` on
 * the detail screens that have one.
 *
 * A page whose props say nothing extra still gets its section and its own title, which is
 * the minimum that stops the header being blank.
 */
export interface Crumb {
  label: string
  /** Absent on the last one: it is the page you are already looking at. */
  href?: string
}

/** The title of each page, by the component name its controller returned. */
const TITLES: Record<string, string> = {
  'auth/Profile': 'Profile',
  'auth/Security': 'Security',
  'auth/ApiTokens': 'API tokens',
  'auth/AdminAccounts': 'Accounts',
  'org/OrganizationList': 'Organizations',
  'org/OrganizationOverview': 'Overview',
  'org/MemberList': 'Members',
  'org/AdminOrganizationList': 'Tenants',
  'org/AdminPlanList': 'Plans',
  'project/Dashboard': 'Projects',
  'project/ProjectOverview': 'Overview',
  'project/ProjectSettings': 'Settings',
  'project/NewService': 'New service',
  'service/ServiceOverview': 'Overview',
  'service/ServiceSettings': 'Settings',
  'service/ServiceEnvironment': 'Environment',
  'service/ServiceVolumes': 'Volumes',
  'service/ServiceTasks': 'Scheduled tasks',
  'deploy/DeploymentList': 'Deployments',
  'domain/ServiceDomains': 'Domains',
  'files/FileManager': 'Files',
  'files/Terminal': 'Terminal',
  'stats/ServiceLogs': 'Logs',
  'stats/ServiceMetrics': 'Metrics',
  'stats/AdminNodeMetrics': 'Metrics',
  'database/DatabaseList': 'Databases',
  'database/AdminDatabaseEngineList': 'Database engines',
  'backup/BackupList': 'Backups',
  'backup/Destinations': 'Destinations',
  'backup/Snapshots': 'Snapshots',
  'backup/AdminDestinations': 'Backup destinations',
  'node/AdminNodeList': 'Nodes',
  'audit/AdminAudit': 'Audit log',
  'jobs/AdminJobs': 'Jobs',
  'error/Error': 'Something went wrong',
}

export function useBreadcrumbs(): Crumb[] {
  const page = usePage()
  const props = page.props as Record<string, unknown>
  const trail: Crumb[] = []

  const section = sectionFor(page.url)
  if (section) {
    trail.push({label: section.label, href: section.href})
  }

  const project = reference(props.project)
  if (project) {
    trail.push({label: project.name, href: `/projects/${project.id}`})
  }

  const service = reference(props.service)
  if (service) {
    trail.push({label: service.name, href: `/services/${service.id}`})
  }

  const leaf = leafLabel(page.component, props)
  const previous = trail.length > 0 ? trail[trail.length - 1] : undefined
  if (leaf && previous?.label !== leaf) {
    trail.push({label: leaf})
  }

  // The last crumb is the current page; a link to where you already are is noise.
  const last = trail[trail.length - 1]
  if (last) {
    delete last.href
  }
  return trail
}

/** The current page's own title, which is also what the header renders large. */
export function usePageTitle(): string {
  const trail = useBreadcrumbs()
  return trail[trail.length - 1]?.label ?? 'wisper'
}

function leafLabel(component: string, props: Record<string, unknown>): string | null {
  switch (component) {
    case 'org/OrganizationOverview':
      return text(record(props.organization)?.name) ?? 'Overview'
    case 'org/AdminOrganizationDetail':
      return text(record(props.tenant)?.name) ?? 'Tenant'
    case 'org/AdminPlanDetail':
      return text(record(props.plan)?.name) ?? 'Plan'
    case 'deploy/DeploymentDetail': {
      const sequence = record(props.deployment)?.sequence
      return typeof sequence === 'number' ? `Deployment #${sequence}` : 'Deployment'
    }
    case 'database/DatabaseDetail':
      return text(record(props.database)?.name) ?? 'Database'
    case 'database/AdminDatabaseEngineDetail':
      return text(record(props.engine)?.engineLabel) ?? 'Engine'
    case 'node/AdminNodeDetail':
      return text(record(record(props.node)?.summary)?.name) ?? 'Node'
    case 'backup/RestoreRun': {
      const target = text(record(props.restore)?.targetLabel)
      return target ? `Restore of ${target}` : 'Restore'
    }
    default:
      return TITLES[component] ?? null
  }
}

/** An `{id, name}` pair, when the prop is one. */
function reference(value: unknown): {id: string; name: string} | null {
  const shape = record(value)
  if (!shape) {
    return null
  }
  // `ServiceLocation` and `DeploymentTarget` call it `serviceId`; every other shape in
  // pages.md calls its own key `id`.
  const id = text(shape.id) ?? text(shape.serviceId)
  const name = text(shape.name)
  return id && name ? {id, name} : null
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}
