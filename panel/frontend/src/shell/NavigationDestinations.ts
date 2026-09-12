import type {IconName} from './Icon'

/**
 * Everywhere the chrome can take you.
 *
 * One list, read by the sidebar, the phone's bottom bar and the navigation drawer, so a
 * screen cannot exist in one of the three and be missing from the others. Every URL here
 * is a prefix `docs/contracts/panel-http.md` assigns to a package, and every screen that
 * contract lists has a way in - the predecessor's failure was a menu full of doors with
 * nothing behind them, and the mirror of that failure is a room with no door.
 */
export interface Destination {
  href: string
  label: string
  labelKey?: string
  icon: IconName
  /**
   * Other path prefixes this destination is the home of. A service lives under
   * `/services/{id}` but belongs to Projects, and the highlight should say so.
   */
  owns?: string[]
}

const PROJECTS: Destination = {
  href: '/',
  label: 'Projects',
  labelKey: 'shell.nav.projects',
  icon: 'projects',
  owns: ['/projects', '/services'],
}
const DATABASES: Destination = {
  href: '/databases',
  label: 'Databases',
  labelKey: 'shell.nav.databases',
  icon: 'database',
}
const BACKUPS: Destination = {
  href: '/backups',
  label: 'Backups',
  labelKey: 'shell.nav.backups',
  icon: 'backup',
}
const ORGANIZATIONS: Destination = {
  href: '/orgs',
  label: 'Organizations',
  labelKey: 'shell.nav.organizations',
  icon: 'organization',
}

/** The tenant's own screens. */
export const WORKSPACE: Destination[] = [PROJECTS, DATABASES, BACKUPS, ORGANIZATIONS]

/**
 * The tabs a thumb reaches for, plus the drawer button the bar adds itself.
 *
 * Three and not four: the fourth slot is the drawer, and everything else - the
 * organization list, this account's settings, the platform screens - lives behind it.
 * Five 75px targets in a row is how a bottom bar becomes unusable.
 */
export const PHONE_TABS: Destination[] = [PROJECTS, DATABASES, BACKUPS]

/** This account, rather than any organization. */
export const ACCOUNT_SETTINGS: Destination[] = [
  {href: '/settings/profile', label: 'Profile', labelKey: 'shell.nav.profile', icon: 'account'},
  {href: '/settings/security', label: 'Security', labelKey: 'shell.nav.security', icon: 'shield'},
  {href: '/settings/tokens', label: 'API tokens', labelKey: 'shell.nav.apiTokens', icon: 'key'},
]

/** `/admin/**`, behind ROLE_ADMIN. The server checks; this only decides what is drawn. */
export const PLATFORM: Destination[] = [
  {href: '/admin/nodes', label: 'Nodes', labelKey: 'shell.nav.nodes', icon: 'node'},
  {href: '/admin/placement', label: 'Placement', labelKey: 'shell.nav.placement', icon: 'node'},
  {href: '/admin/organizations', label: 'Tenants', labelKey: 'shell.nav.tenants', icon: 'organization'},
  {href: '/admin/plans', label: 'Plans', labelKey: 'shell.nav.plans', icon: 'plan'},
  {href: '/admin/accounts', label: 'Accounts', labelKey: 'shell.nav.accounts', icon: 'account'},
  {href: '/admin/databases', label: 'Database engines', labelKey: 'shell.nav.databaseEngines', icon: 'database'},
  {href: '/admin/backups', label: 'Backup destinations', labelKey: 'shell.nav.backupDestinations', icon: 'backup'},
  {href: '/admin/audit', label: 'Audit log', labelKey: 'shell.nav.auditLog', icon: 'audit'},
  {href: '/admin/jobs', label: 'Jobs', labelKey: 'shell.nav.jobs', icon: 'jobs'},
]

/** The path part of an Inertia URL, without its query string or trailing slash. */
export function pathOf(url: string): string {
  const cut = url.search(/[?#]/)
  const bare = cut < 0 ? url : url.slice(0, cut)
  return bare.length > 1 && bare.endsWith('/') ? bare.slice(0, -1) : bare
}

/** Whether `url` is this destination or something underneath it. */
export function isDestinationActive(url: string, destination: Destination): boolean {
  const path = pathOf(url)
  if (destination.href === '/') {
    return path === '/' || covers(path, destination.owns)
  }
  return path === destination.href || path.startsWith(`${destination.href}/`) || covers(path, destination.owns)
}

function covers(path: string, owned: string[] | undefined): boolean {
  return (owned ?? []).some((prefix) => path === prefix || path.startsWith(`${prefix}/`))
}

/**
 * The section a URL belongs to, for the first breadcrumb.
 *
 * Platform first: `/admin/databases` is the engine list, not the customer's database
 * list, and checking the tenant destinations first would answer with the wrong one.
 */
export function sectionFor(url: string): Destination | null {
  const path = pathOf(url)
  if (path.startsWith('/admin')) {
    return PLATFORM.find((entry) => isDestinationActive(path, entry)) ?? null
  }
  if (path.startsWith('/settings')) {
    return ACCOUNT_SETTINGS.find((entry) => isDestinationActive(path, entry)) ?? null
  }
  return WORKSPACE.find((entry) => isDestinationActive(path, entry)) ?? null
}
