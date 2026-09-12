import {t} from '@/i18n'
import {ACCOUNT_SETTINGS, Tabs} from '@/shell'

/**
 * The strip across the top of `/settings`.
 *
 * The three destinations come from `NavigationDestinations`, which is the same list the
 * sidebar and the navigation drawer read. A hand-written copy here would be a fourth
 * place a settings screen could exist in one menu and be missing from another - the
 * failure this panel was rewritten to avoid.
 *
 * `/admin/accounts` is deliberately not in this strip. It is an operator screen reached
 * from the platform section, not one of the customer's own settings, and putting it here
 * would show every customer a tab that answers 403.
 */
export function SettingsTabs() {
  return (
    <Tabs
      label={t('auth.settings.tabsLabel')}
      items={ACCOUNT_SETTINGS.map((destination) => ({
        href: destination.href,
        label: destination.label,
      }))}
    />
  )
}
