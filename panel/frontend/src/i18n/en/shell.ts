import type {Catalog} from '../translate'

/**
 * The application chrome: navigation, the account menu, the states every screen shares.
 *
 * <p>Keys are `shell.<area>.<thing>`. Anything that appears on more than one screen but
 * belongs to no single feature lives here; anything that belongs to a feature lives in
 * that feature's file.
 */
export const messages: Catalog = {
  'shell.skipToContent': 'Skip to content',

  'shell.nav.projects': 'Projects',
  'shell.nav.databases': 'Databases',
  'shell.nav.backups': 'Backups',
  'shell.nav.organizations': 'Organizations',
  'shell.nav.profile': 'Profile',
  'shell.nav.security': 'Security',
  'shell.nav.apiTokens': 'API tokens',
  'shell.nav.nodes': 'Nodes',
  'shell.nav.placement': 'Placement',
  'shell.nav.tenants': 'Tenants',
  'shell.nav.plans': 'Plans',
  'shell.nav.accounts': 'Accounts',
  'shell.nav.databaseEngines': 'Database engines',
  'shell.nav.backupDestinations': 'Backup destinations',
  'shell.nav.auditLog': 'Audit log',
  'shell.nav.jobs': 'Jobs',
  'shell.nav.sectionAccount': 'Account',
  'shell.nav.sectionPlatform': 'Platform',
  'shell.nav.open': 'Open navigation',

  'shell.account.title': 'Account',
  'shell.account.label': 'Account: {name}',
  'shell.account.platformOperator': 'Platform operator',
  'shell.account.signOut': 'Sign out',
  'shell.account.theme': 'Theme',
  'shell.account.language': 'Language',

  'shell.theme.light': 'Light',
  'shell.theme.dark': 'Dark',
  'shell.theme.system': 'Match the system',

  'shell.action.close': 'Close',
  'shell.action.cancel': 'Cancel',
  'shell.action.save': 'Save',
  'shell.action.copy': 'Copy',
  'shell.action.copied': 'Copied',
  'shell.action.retry': 'Try again',

  'shell.state.loading': 'Loading',
  'shell.state.empty': 'Nothing here yet',
  'shell.state.error': 'Something went wrong',

  'shell.pagination.summary': '{from}-{to} of {total} {unit}',
  'shell.pagination.previous': 'Previous',
  'shell.pagination.next': 'Next',

  'shell.time.justNow': 'just now',
  'shell.confirm.typeToConfirm': 'Type {phrase} to confirm',
}
