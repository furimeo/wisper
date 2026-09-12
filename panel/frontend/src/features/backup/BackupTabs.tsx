import {t} from '@/i18n'
import {Tabs} from '@/shell'

/**
 * The three backup screens, one organization at a time.
 *
 * The organization is in the path rather than remembered in a session, so a customer with
 * three tenants can have three of these open and the chrome cannot disagree with the
 * content about which one is being looked at. That means these links have to carry it too.
 *
 * A restore run's own page is deliberately not a tab. It is reached from a snapshot and
 * from the history list, and it belongs to one run rather than to the section.
 */
export function BackupTabs({organizationId}: {organizationId: string}) {
  return (
    <Tabs
      label={t('backup.tabs.label')}
      items={[
        {href: `/backups/${organizationId}`, label: t('backup.tabs.schedules')},
        {href: `/backups/${organizationId}/snapshots`, label: t('backup.tabs.snapshots')},
        {href: `/backups/${organizationId}/destinations`, label: t('backup.tabs.destinations')},
      ]}
    />
  )
}
