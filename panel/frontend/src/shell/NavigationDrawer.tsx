import {Drawer} from './Drawer'
import {NavigationList} from './NavigationList'
import {OrganizationSwitcher} from './OrganizationSwitcher'

/**
 * Everything the sidebar holds, for a phone.
 *
 * Opened from the menu button in the header and from the fourth slot in the bottom bar,
 * which is why the open state belongs to `AppLayout` rather than to either of them.
 * Selecting anything closes it: a navigation panel still covering the page it navigated
 * to is the single most common bug in a mobile drawer.
 */
export function NavigationDrawer({open, onClose}: {open: boolean; onClose: () => void}) {
  return (
    <Drawer open={open} onClose={onClose} title="wisper">
      <div className="flex flex-col gap-4">
        <OrganizationSwitcher className="w-full border border-ink-200 dark:border-ink-700" />
        <NavigationList onNavigate={onClose} />
      </div>
    </Drawer>
  )
}
