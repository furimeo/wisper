import {Link} from '@inertiajs/react'
import {useState} from 'react'

import {Badge} from './Badge'
import {Drawer} from './Drawer'
import {Icon} from './Icon'
import {cx} from './cx'
import type {OrganizationSummary} from './shellProps'
import {useShellProps} from './shellProps'

/**
 * Which tenant the chrome is talking about, and how to change it.
 *
 * Selecting one navigates to `/orgs/{id}`. That is deliberate rather than a silent
 * client-side switch: `org.CurrentOrganizationProps` picks the current organization from
 * the `{organizationId}` in the URL first and remembers it in the session, so visiting
 * the organization is both the switch and the confirmation that it happened. A switcher
 * that changed a hidden value would leave the address bar describing the previous tenant.
 *
 * An invitation is listed and is not selectable. It is in `organizations` so the list
 * screen can offer it, but making it current would put a tenant this account cannot read
 * into the chrome of every page.
 */
export function OrganizationSwitcher({className}: {className?: string}) {
  const {organization, organizations} = useShellProps()
  const [open, setOpen] = useState(false)

  const memberships = organizations.filter((entry) => entry.accepted)
  const invitations = organizations.filter((entry) => !entry.accepted)

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-haspopup="dialog"
        className={cx(
          'flex min-w-0 touch-target items-center gap-2 rounded-lg px-2 text-left',
          'hover:bg-ink-100 dark:hover:bg-ink-800',
          className,
        )}
      >
        <span className="flex size-7 shrink-0 items-center justify-center rounded-md bg-accent-500/15 text-xs font-semibold text-accent-600 dark:text-accent-400">
          {initials(organization)}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium text-ink-900 dark:text-ink-100">
            {organization ? organization.name : 'No organization'}
          </span>
          {organization?.suspended ? (
            <span className="block text-xs text-failed">Suspended</span>
          ) : null}
        </span>
        <Icon name="chevronUpDown" className="size-4 shrink-0 text-ink-400" />
      </button>

      <Drawer open={open} onClose={() => setOpen(false)} title="Organizations">
        {memberships.length === 0 && invitations.length === 0 ? (
          <p className="px-3 py-4 text-sm text-ink-600 dark:text-ink-400">
            You do not belong to an organization yet. Create one from the Organizations
            screen and your projects will live inside it.
          </p>
        ) : null}

        <ul className="flex flex-col">
          {memberships.map((entry) => (
            <li key={entry.id}>
              <Link
                href={`/orgs/${entry.id}`}
                onClick={() => setOpen(false)}
                aria-current={entry.id === organization?.id ? 'true' : undefined}
                className={cx(
                  'flex touch-target items-center gap-3 rounded-lg px-3 text-sm',
                  entry.id === organization?.id
                    ? 'bg-accent-500/10 text-accent-600 dark:text-accent-400'
                    : 'text-ink-800 hover:bg-ink-100 dark:text-ink-100 dark:hover:bg-ink-800',
                )}
              >
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{entry.name}</span>
                  <span className="block truncate text-xs text-ink-500 dark:text-ink-400">
                    {entry.role.toLowerCase()}
                  </span>
                </span>
                {entry.suspended ? <Badge tone="failed">Suspended</Badge> : null}
                {entry.id === organization?.id ? (
                  <Icon name="check" className="size-4 shrink-0" />
                ) : null}
              </Link>
            </li>
          ))}
        </ul>

        {invitations.length > 0 ? (
          <>
            <p className="mt-4 px-3 text-xs font-semibold uppercase tracking-wide text-ink-500 dark:text-ink-400">
              Invitations
            </p>
            <ul className="mt-1 flex flex-col">
              {invitations.map((entry) => (
                <li
                  key={entry.id}
                  className="flex touch-target items-center gap-3 px-3 text-sm text-ink-600 dark:text-ink-400"
                >
                  <span className="min-w-0 flex-1 truncate">{entry.name}</span>
                  <Badge tone="accent">Pending</Badge>
                </li>
              ))}
            </ul>
            <p className="mt-2 px-3 text-xs text-ink-500 dark:text-ink-400">
              Answer an invitation from the Organizations screen.
            </p>
          </>
        ) : null}

        <Link
          href="/orgs"
          onClick={() => setOpen(false)}
          className="mt-4 flex touch-target items-center gap-3 rounded-lg px-3 text-sm font-medium text-accent-600 hover:bg-ink-100 dark:text-accent-400 dark:hover:bg-ink-800"
        >
          <Icon name="organization" className="size-4" />
          Manage organizations
        </Link>
      </Drawer>
    </>
  )
}

/** Two letters for the square, so the switcher is recognisable before it is read. */
function initials(organization: OrganizationSummary | null): string {
  if (!organization) {
    return '—'
  }
  const words = organization.name.split(/\s+/).filter((word) => word.length > 0)
  const first = words[0]?.charAt(0) ?? organization.name.charAt(0)
  const second = words.length > 1 ? (words[1]?.charAt(0) ?? '') : ''
  return `${first}${second}`.toUpperCase()
}
